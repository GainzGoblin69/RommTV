// LibretroDroid 0.14.0 (Filippo Scognamiglio, GPL-3.0), vendored so RomM TV can add RetroAchievements
// (rcheevos, MIT) to the emulator loop. Upstream: https://github.com/Swordfish90/LibretroDroid
plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.swordfish.libretrodroid"

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(deps.libs.androidx.lifecycle.runtime)
}
