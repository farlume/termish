import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
                "-lssh2", "-lssl", "-lcrypto", "-lz",
                "-framework", "Security",
            )
        }
        target.compilations.getByName("main").cinterops.create("libssh2") {
            defFile(libssh2Def)
            compilerOpts("-I${nativeRoot.resolve("include")}")
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
        }
    }
}

android {
    namespace = "dev.mssh.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.mssh.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0"
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