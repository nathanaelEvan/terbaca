package app.terbaca.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates baseline profile rules for cold startup and document surface gestures
 * and slide flips (PRD §4.6, M1-10). Copy the generated file to app/src/main/baseline-prof.txt.
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
}
