package app.terbaca.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates baseline profile rules targeting cold startup and initial document surface rendering
 * for Terbaca per PRD §4.6 and M1-10.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() = baselineProfileRule.collect(
        packageName = "app.terbaca",
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
    }
}
