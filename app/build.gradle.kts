plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val terbacaAbis = providers.gradleProperty("terbaca.abis")
    .getOrElse("arm64-v8a")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

abstract class CopyLicensesTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val src = inputDir.get().asFile
        val dest = outputDir.get().asFile
        dest.mkdirs()
        File(src, "LICENSE.pdfium-binaries").copyTo(File(dest, "LICENSE"), overwrite = true)
        File(src, "LICENSE.pdfium-binaries").copyTo(File(dest, "LICENSE.pdfium-binaries"), overwrite = true)
        File(src, "THIRD_PARTY_NOTICES").copyTo(File(dest, "THIRD_PARTY_NOTICES"), overwrite = true)
    }
}

val copyLicenseAssets by tasks.registering(CopyLicensesTask::class) {
    inputDir.set(file("${project(":core:pdfium").projectDir}/src/main/cpp"))
    outputDir.set(layout.buildDirectory.dir("generated/license-assets"))
}

android {
    namespace = "app.terbaca"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.terbaca"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Release target is arm64-v8a only (ADR-0001). CI passes -Pterbaca.abis=x86_64
            // to run instrumented tests on an x86_64 emulator.
            abiFilters.addAll(terbacaAbis)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release-equivalent, non-debuggable build for Macrobenchmark (PRD §7). Debug-signed so it
        // can be installed on the test device; never distributed.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyLicenseAssets,
            CopyLicensesTask::outputDir
        )
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:pdfium"))
    implementation(project(":core:render"))
    implementation(project(":core:data"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:slides"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.profileinstaller)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
