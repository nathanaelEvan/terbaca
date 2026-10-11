package app.terbaca.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates baseline profile rules for cold startup, VIEW-intent open, library tap,
 * document surface gestures, and slide flips (PRD §4.6, §7, M1-10, M6-10).
 * Copy the generated file to app/src/main/baseline-prof.txt.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true
    ) {
        // Cold start lands on the library (PRD §7: cold start → library visible).
        pressHome()
        startActivityAndWait()

        // VIEW-intent open path: opens a content:// URI via Intent.ACTION_VIEW (M6-10).
        val mediaId = resolveMediaId(device, "bench-paper_two_column.pdf")
        if (mediaId != null) {
            val contentUri = Uri.parse("content://media/external/file/$mediaId")
            pressHome()
            startActivityAndWait { intent ->
                intent.action = Intent.ACTION_VIEW
                intent.setDataAndType(contentUri, "application/pdf")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Thread.sleep(1_500)

            // Library-tap open path: return to library and tap the recent item (M6-10).
            pressHome()
            startActivityAndWait()
            Thread.sleep(1_000)
            val recentCard = device.findObject(By.textContains("paper_two_column"))
                ?: device.findObject(By.textContains("bench"))
            recentCard?.click()
            Thread.sleep(1_500)
        }

        // Then the reader on a corpus document for the gesture paths.
        pressHome()
        startActivityAndWait { it.putExtra(EXTRA_CORPUS_FILE, "corpus/paper_two_column.pdf") }
        Thread.sleep(1_500)
        exerciseReader()

        // And slide mode on the deck, for the pager paths (PRD §7 slide flip).
        pressHome()
        startActivityAndWait { it.putExtra(EXTRA_CORPUS_FILE, "corpus/deck_16_9.pdf") }
        Thread.sleep(1_500)
        val w = device.displayWidth
        val cy = device.displayHeight / 2
        repeat(4) {
            device.swipe(w * 4 / 5, cy, w / 5, cy, 8)
            Thread.sleep(500)
        }
        repeat(4) {
            device.swipe(w / 5, cy, w * 4 / 5, cy, 8)
            Thread.sleep(500)
        }
    }

    private fun resolveMediaId(device: UiDevice, displayName: String): String? {
        val out = device.executeShellCommand(
            "content query --uri content://media/external/file --projection _id --where \"_display_name='$displayName'\""
        )
        val id = Regex("_id=(\\d+)").find(out)?.groupValues?.get(1)
        if (id != null) return id
        val fallbackOut = device.executeShellCommand(
            "content query --uri content://media/external/file --projection _id:_display_name"
        )
        return Regex("_id=(\\d+), _display_name=$displayName").find(fallbackOut)?.groupValues?.get(1)
    }
}
