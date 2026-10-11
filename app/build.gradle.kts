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

// Release signing (M5-06, docs/release-signing.md). The keystore and its passwords never live in
// the repo: they come from Gradle properties (~/.gradle/gradle.properties) or environment
// variables. Without them the release build is left unsigned.
fun releaseSigningValue(name: String): String? =
    providers.gradleProperty("terbaca.$name").orElse(providers.environmentVariable("TERBACA_${name.uppercase()}"))
        .orNull?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSigningValue("storeFile")
val releaseStorePassword = releaseSigningValue("storePassword")
val releaseKeyAlias = releaseSigningValue("keyAlias")
val releaseKeyPassword = releaseSigningValue("keyPassword")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }
if (!hasReleaseSigning &&
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).any { it != null }
) {
    logger.warn("Terbaca: only some release signing values are set; the release build will be unsigned.")
}

val copyLicenseAssets by tasks.registering(CopyLicensesTask::class) {
    inputDir.set(file("${project(":core:pdfium").projectDir}/src/main/cpp"))
    outputDir.set(layout.buildDirectory.dir("generated/license-assets"))
}

android {
    namespace = "app.terbaca"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "app.terbaca"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Release target is arm64-v8a only (ADR-0001). CI passes -Pterbaca.abis=x86_64
            // to run instrumented tests on an x86_64 emulator.
            abiFilters.addAll(terbacaAbis)
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
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
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
