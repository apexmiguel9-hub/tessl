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
        // MUST stay <= 28. AOSP system/sepolicy private/app_neverallows.te:
        //
        //   # Block calling execve() on files in an apps home directory.
        //   # This is a W^X violation. For compatibility, allow for
        //   # targetApi <= 28.
        //   neverallow { all_untrusted_apps
        //     -untrusted_app_25 -untrusted_app_27 -runas_app
        //   } { app_data_file privapp_data_file }:file execute_no_trans;
        //
        // So only targetSdk 25/26-28 apps are given execute_no_trans on their
        // own data dir; everything >= 29 runs in plain `untrusted_app` and is
        // denied. Confirmed on an Android 16 device:
        //
        //   avc: denied { execute_no_trans } for comm="tessl-session-s"
        //     path=".../files/usr/bin/bash"
        //     scontext=u:r:untrusted_app  tcontext=u:oject_r:app_data_file
        //
        // There is no workaround from inside the app: no manifest flag, no
        // permission, no Java API. Every Termux fork sets targetSdk 28 for
        // this reason. The cost is that the Play Store will not accept the
        // build, so distribution has to be F-Droid / GitHub.
        targetSdk = 28
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
        // Registered unconditionally so the type exists, but only *applied*
        // to the release build type when the keystore is actually present,
        // otherwise packageRelease fails instead of emitting an unsigned apk.
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
            signingConfig = if (rootProject.file(
                    System.getenv("TESSL_KEYSTORE") ?: "keystore/release.jks"
                ).exists()
            ) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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
