package app.terbaca

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests validating release packaging configuration and APK size budgets per PRD §7 and M5-03.
 *
 * Requirements:
 * - APK size <= 15 MB (arm64-v8a release APK, R8 full mode, resource shrinking, native strip).
 * - Single target ABI (arm64-v8a per ADR-0001).
 * - Explicit R8 full mode and NDK toolchain configuration.
 */
class ApkPackagingBudgetUnitTest {

    companion object {
        const val MAX_RELEASE_APK_BYTES = 15L * 1024L * 1024L // 15 MB PRD §7 budget
        const val STRIPPED_JNI_MAX_BYTES = 100L * 1024L // libterbaca_pdfium.so stripped should be ~25 KB
    }

    @Test
    fun buildConfiguration_verifiesR8FullModeAndPackagingSettings() {
        val gradleProps = File("../gradle.properties")
        assertTrue("gradle.properties must exist", gradleProps.exists())
        val propsContent = gradleProps.readText()
        assertTrue(
            "android.enableR8.fullMode must be enabled in gradle.properties",
            propsContent.contains("android.enableR8.fullMode=true")
        )

        val appBuildGradle = File("build.gradle.kts")
        assertTrue("app/build.gradle.kts must exist", appBuildGradle.exists())
        val gradleContent = appBuildGradle.readText()
        assertTrue("isMinifyEnabled must be true in release", gradleContent.contains("isMinifyEnabled = true"))
        assertTrue("isShrinkResources must be true in release", gradleContent.contains("isShrinkResources = true"))
        assertTrue("ndkVersion must be specified in app", gradleContent.contains("ndkVersion = \"27.2.12479018\""))
    }

    @Test
    fun releaseApk_whenAssembled_satisfiesSizeAndArchitectureBudgets() {
        val releaseApk = File("build/outputs/apk/release/app-release-unsigned.apk")
        if (!releaseApk.exists()) {
            // Optional if release APK has not been assembled yet in a given test run
            return
        }

        val apkSize = releaseApk.length()
        assertTrue(
            "Release APK ($apkSize bytes) must be <= 15 MB ($MAX_RELEASE_APK_BYTES bytes)",
            apkSize <= MAX_RELEASE_APK_BYTES
        )

        ZipFile(releaseApk).use { zip ->
            // Verify single target architecture (arm64-v8a)
            var hasArm64Pdfium = false
            var hasArm64Terbaca = false
            var hasOtherAbi = false

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name.startsWith("lib/")) {
                    if (name.startsWith("lib/arm64-v8a/")) {
                        if (name.endsWith("libpdfium.so")) hasArm64Pdfium = true
                        if (name.endsWith("libterbaca_pdfium.so")) {
                            hasArm64Terbaca = true
                            assertTrue(
                                "libterbaca_pdfium.so must be stripped and <= 100 KB (actual: ${entry.size} bytes)",
                                entry.size <= STRIPPED_JNI_MAX_BYTES
                            )
                        }
                    } else if (name.endsWith(".so")) {
                        hasOtherAbi = true
                    }
                }
            }

            assertTrue("Release APK must include arm64-v8a libpdfium.so", hasArm64Pdfium)
            assertTrue("Release APK must include arm64-v8a libterbaca_pdfium.so", hasArm64Terbaca)
            assertFalse("Release APK must not package non-arm64 ABIs", hasOtherAbi)

            // Verify DEX presence
            val dexEntry = zip.getEntry("classes.dex")
            assertNotNull("Release APK must contain classes.dex", dexEntry)
        }
    }
}
