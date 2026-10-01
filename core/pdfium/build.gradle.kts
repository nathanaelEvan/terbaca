plugins {
    alias(libs.plugins.android.library)
}

val terbacaAbis = providers.gradleProperty("terbaca.abis")
    .getOrElse("arm64-v8a")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

android {
    namespace = "app.terbaca.core.pdfium"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Release target is arm64-v8a only (ADR-0001). CI passes -Pterbaca.abis=x86_64
            // to run instrumented tests on an x86_64 emulator.
            abiFilters.addAll(terbacaAbis)
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
