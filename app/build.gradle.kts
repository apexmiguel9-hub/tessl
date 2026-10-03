plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.apexmiguel9.termux"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "io.github.apexmiguel9.termux"
        // Android 11 is the floor: user-facing requirement, and it is also
        // where Vulkan 1.3 class drivers start appearing.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
                cppFlags += "-std=c11"
                cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror=implicit-function-declaration")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Committed throwaway key so `adb install` works with no setup and the
        // same signature on every machine and in CI.
        create("shared") {
            storeFile = rootProject.file("keystore/debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Real key comes from the environment so no secret is in the repo.
        create("release") {
            val f = rootProject.file(
                System.getenv("TESSL_KEYSTORE") ?: "keystore/release.jks"
            )
            if (f.exists()) {
                storeFile = f
                storePassword = System.getenv("TESSL_STORE_PASS")
                keyAlias = System.getenv("TESSL_KEY_ALIAS")
                keyPassword = System.getenv("TESSL_KEY_PASS")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The vendored terminal-emulator is Java 8 source; it compiles fine
        // under 17 and Kotlin/Java live in the same JVM, no porting needed.
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
