plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.carlo.gbafg"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.carlo.gbafg"
        minSdk = 29
        targetSdk = 35
        versionCode = 26
        versionName = "0.5.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-DNDEBUG", "-fvisibility=hidden", "-fvisibility-inlines-hidden",
                    "-ffunction-sections", "-fdata-sections")
                cFlags += listOf("-O2", "-DNDEBUG")
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_PLATFORM=android-29",
                    "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--gc-sections")
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
        create("fixed") {
            // fixed key so every new APK installs over the previous one
            storeFile = rootProject.file("keystore/gbafg.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { jniLibs { useLegacyPackaging = false } }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
