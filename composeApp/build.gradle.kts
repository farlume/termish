import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
    compilerOptions {
        // expect/actual class declarations are intentional KMP seams in this project.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val nativeRoot = rootProject.file("iosApp/native")
    val libssh2Def = project.file("src/nativeInterop/cinterop/libssh2.def")
    val screenPlayerDef = project.file("src/nativeInterop/cinterop/screen_player.def")
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
                "-framework", "AVFoundation",
                "-framework", "CoreMedia",
                "-framework", "Security",
                "-framework", "VideoToolbox",
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
        target.compilations.getByName("main").cinterops.create("screenPlayer") {
            defFile(screenPlayerDef)
            compilerOpts("-I${project.file("src/nativeInterop/cinterop")}")
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/screenService/kotlin"))
            kotlin.srcDir(layout.buildDirectory.dir("generated/agentBridge/kotlin"))
        }
        val androidMain by getting
        val androidUnitTest by getting

        // iOS 共享源集（gradle.properties 中关闭了默认层级模板）。
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
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.code)
            implementation(libs.markdown.renderer.m3)
        }

        androidMain.dependencies {
            implementation(libs.sshj)
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.documentfile)
            implementation(libs.bouncycastle.prov)
            implementation(libs.bouncycastle.pkix)
            implementation(libs.okhttp)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.robolectric)
            implementation(libs.slf4j.nop)
            // Dispatchers.setMain + StandardTestDispatcher：TerminalController 的
            // 输出消费循环固定在 Dispatchers.Main，测试需要可控的主调度器
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "dev.termish.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

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
        versionCode = 40
        versionName = "1.8.2"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/*.MF"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/DEPENDENCIES"
            // SSH 仅使用 BC 的经典密钥/证书算法；Picnic 后量子参数资源约 1.2MB，
            // R8 不会自动移除 jar resource，显式排除可缩小 APK 且不影响 SSH。
            excludes += "/org/bouncycastle/pqc/crypto/picnic/*.properties"
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
