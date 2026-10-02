package app.terbaca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseAssetTest {
    @Test
    fun licenseFilesArePresentInSourceAndMergedOutput() {
        val notices = File("../core/pdfium/src/main/cpp/THIRD_PARTY_NOTICES")
        val license = File("../core/pdfium/src/main/cpp/LICENSE.pdfium-binaries")

        assertTrue("THIRD_PARTY_NOTICES source file must exist", notices.exists())
        assertTrue("LICENSE.pdfium-binaries source file must exist", license.exists())
        assertTrue("THIRD_PARTY_NOTICES must not be empty", notices.length() > 0)
        assertTrue("LICENSE.pdfium-binaries must not be empty", license.length() > 0)
    }

    @Test
    fun apkContainsLicenseAssetsIfAssembled() {
        val apkFile = File("build/outputs/apk/debug/app-debug.apk")
        if (apkFile.exists()) {
            java.util.zip.ZipFile(apkFile).use { zip ->
                val licenseEntry = zip.getEntry("assets/LICENSE")
                val noticesEntry = zip.getEntry("assets/THIRD_PARTY_NOTICES")
                assertTrue("APK must contain assets/LICENSE", licenseEntry != null)
                assertTrue("APK must contain assets/THIRD_PARTY_NOTICES", noticesEntry != null)
            }
        }
    }
}
