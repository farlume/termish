import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files
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
    packageOfResClass = "dev.mssh.generated.resources"
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
    val moshptyDef = project.file("src/nativeInterop/cinterop/moshpty.def")

    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()

    listOf(
        iosArm64 to nativeRoot.resolve("lib/device"),
        iosSimulatorArm64 to nativeRoot.resolve("lib/sim"),
    ).forEach { (target, libDir) ->
        target.binaries.framework {
            baseName = "Mssh"
            isStatic = true
            binaryOption("bundleId", "dev.mssh.app.Mssh")
            linkerOpts(
                "-L$libDir",
                "-lssh2", "-lssl", "-lcrypto", "-lmoshpty", "-lz",
                "-framework", "Security",
            )
        }
        target.compilations.getByName("main").cinterops.create("libssh2") {
            defFile(libssh2Def)
            compilerOpts("-I${nativeRoot.resolve("include")}")
        }
        target.compilations.getByName("main").cinterops.create("moshpty") {
            defFile(moshptyDef)
            compilerOpts(
                "-I${rootProject.file("scripts/ios")}",
                "-I${nativeRoot.resolve("include")}",
            )
        }
    }

    sourceSets {
        val desktopMain by getting
        val androidMain by getting

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
            implementation(libs.pty4j)
        }
    }
}

// 桌面（开发/测试 harness）运行与打包入口
compose.desktop.application {
    mainClass = "dev.mssh.MainKt"
    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        packageName = "MSSH"
        packageVersion = "1.0.0"
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

// 开发态 `./gradlew run` 时 macOS Dock 悬停名显示 "MSSH" 而不是 "java"：
// macOS 的 Dock 悬停名取进程名，-Xdock:name 只能改菜单栏（JDK-8077172），
// 所以用一个名为 MSSH 的符号链接指向 java 来启动，进程名即为 MSSH。
afterEvaluate {
    tasks.named<JavaExec>("run") {
        doFirst {
            val javaBin = File(System.getProperty("java.home"), "bin/java")
            val linkDir = File(System.getProperty("user.home"), ".mssh/bin")
            val link = File(linkDir, "MSSH")
            if (!link.exists()) {
                linkDir.mkdirs()
                Files.createSymbolicLink(link.toPath(), javaBin.toPath())
            }
            executable = link.absolutePath
        }
        // 菜单栏应用名（Dock 名由上面的符号链接决定）
        jvmArgs("-Xdock:name=MSSH")
    }
}

android {
    namespace = "dev.mssh.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        // release 签名：密钥放 ~/Documents/秘钥/，密码等参数在项目根 keystore.properties（不入库）。
        // 该文件不存在时跳过签名配置，方便直接构建 debug。
        val props = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        create("release") {
            if (props.containsKey("storeFile")) {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "dev.mssh.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.0.0"
    }
    packaging {
        jniLibs {
            // 强制解压原生库：否则 .so 留在 APK 内，app 无法执行 mosh-client
            // （SELinux 禁止 untrusted_app 执行 app_data_file）
            useLegacyPackaging = true
        }
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
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile?.isFile == true }
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
