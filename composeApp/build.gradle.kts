import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// 终端内置等宽字体（composeResources/font）：固定 Res 生成类的包名
compose.resources {
    packageOfResClass = "dev.termish.generated.resources"
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    val nativeRoot = rootProject.file("iosApp/native")
    val libssh2Def = project.file("src/nativeInterop/cinterop/libssh2.def")
    val zlibDef = project.file("src/nativeInterop/cinterop/zlib.def")

    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()

    listOf(
        iosArm64 to nativeRoot.resolve("lib/device"),
        iosSimulatorArm64 to nativeRoot.resolve("lib/sim"),
    ).forEach { (target, libDir) ->
        target.binaries.framework {
            baseName = "Termish"
            isStatic = true
            binaryOption("bundleId", "dev.termish.app.Termish")
            linkerOpts(
                "-L$libDir",
                "-lssh2", "-lssl", "-lcrypto", "-lz",
                "-framework", "Security",
            )
        }
        target.compilations.getByName("main").cinterops.create("libssh2") {
            defFile(libssh2Def)
            compilerOpts("-I${nativeRoot.resolve("include")}")
        }
        target.compilations.getByName("main").cinterops.create("zlib") {
            defFile(zlibDef)
        }
        target.compilations.getByName("main").cinterops.create("sftpWrite") {
            defFile(project.file("src/nativeInterop/cinterop/sftp_write.def"))
            compilerOpts(
                "-I${nativeRoot.resolve("include")}",
                "-I${project.file("src/nativeInterop/cinterop")}",
            )
        }
    }

    sourceSets {
        val desktopMain by getting
        val androidMain by getting
        val desktopTest by getting

        // Android 与 desktop 共享的 JVM SSH 引擎（sshj）
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.sshj)
            }
        }
        desktopMain.dependsOn(jvmSharedMain)
        androidMain.dependsOn(jvmSharedMain)

        // iOS 共享源集（默认层级模板因上面的显式 dependsOn 被禁用，需手动创建）
        val iosMain by creating {
            dependsOn(commonMain.get())
        }
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.noarg)
            implementation(libs.androidx.lifecycle.viewmodel)
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.documentfile)
            implementation(libs.bouncycastle.prov)
            implementation(libs.bouncycastle.pkix)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.slf4j.nop)
        }

        desktopTest.dependencies {
            // Dispatchers.setMain + StandardTestDispatcher：TerminalController 的
            // 输出消费循环固定在 Dispatchers.Main，测试需要可控的主调度器
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// 桌面（开发/测试 harness）运行与打包入口
compose.desktop.application {
    mainClass = "dev.termish.MainKt"
    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        packageName = "Termish"
        packageVersion = "1.1.3"
        macOS {
            iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
        }
        windows {
            iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
        }
        linux {
            iconFile.set(project.file("src/desktopMain/resources/icon.png"))
        }
    }
}

// 开发态 `./gradlew run` 时 macOS Dock 悬停名显示 "Termish" 而不是 "java"：
// macOS 的 Dock 悬停名取进程名，-Xdock:name 只能改菜单栏（JDK-8077172），
// 所以用一个名为 Termish 的符号链接指向 java 来启动，进程名即为 Termish。
afterEvaluate {
    tasks.named<JavaExec>("run") {
        doFirst {
            val javaBin = File(System.getProperty("java.home"), "bin/java")
            val linkDir = File(System.getProperty("user.home"), ".termish/bin")
            val link = File(linkDir, "Termish")
            if (!link.exists()) {
                linkDir.mkdirs()
                Files.createSymbolicLink(link.toPath(), javaBin.toPath())
            }
            executable = link.absolutePath
        }
        // 菜单栏应用名（Dock 名由上面的符号链接决定）
        jvmArgs("-Xdock:name=Termish")
    }
}

android {
    namespace = "dev.termish.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        // release 签名机密来源（按优先级）：
        //   1. 进程环境变量（CI：GitHub Secrets 直接注入，云端零文件）
        //   2. 项目根 .env（gitignore，前端惯例位置）+ 项目根 termish-release.jks（*.jks 已忽略）
        //   3. keystore.properties（历史兼容）
        // jks 文件不存在时从 ANDROID_KEYSTORE_BASE64 解码到 build/signing/。
        // 都没有时跳过签名，不影响 debug 构建。
        val fileEnv = mutableMapOf<String, String>()
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val t = line.trim()
                if (t.isNotEmpty() && !t.startsWith("#") && '=' in t) {
                    fileEnv[t.substringBefore('=').trim()] = t.substringAfter('=').trim()
                }
            }
        }
        val props = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun secret(envKey: String, propKey: String): String? =
            System.getenv(envKey)?.takeIf { it.isNotEmpty() } ?: fileEnv[envKey] ?: props.getProperty(propKey)
        create("release") {
            var jks = file(secret("ANDROID_KEYSTORE_FILE", "storeFile") ?: "termish-release.jks")
            if (!jks.isFile) jks = rootProject.file("termish-release.jks")
            val b64 = secret("ANDROID_KEYSTORE_BASE64", "keystoreBase64")
            if (!jks.isFile && b64 != null) {
                val out = layout.buildDirectory.dir("signing").get().asFile.apply { mkdirs() }
                    .resolve("termish-release.jks")
                if (!out.isFile) out.writeBytes(Base64.getDecoder().decode(b64))
                jks = out
            }
            storeFile = jks
            storePassword = secret("ANDROID_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = secret("ANDROID_KEY_ALIAS", "keyAlias")
            keyPassword = secret("ANDROID_KEY_PASSWORD", "keyPassword")
        }
    }

    defaultConfig {
        applicationId = "dev.termish.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 8
        versionName = "1.1.3"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/*.MF"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")?.takeIf {
                it.storeFile?.isFile == true && it.storePassword != null && it.keyAlias != null && it.keyPassword != null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
