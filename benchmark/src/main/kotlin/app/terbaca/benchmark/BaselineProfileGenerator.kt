package app.terbaca.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
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

        device.wait(Until.hasObject(By.pkg("app.terbaca")), 5_000)
        Thread.sleep(1000)

        val content = device.findObject(By.pkg("app.terbaca"))
        content?.fling(Direction.DOWN)
        device.waitForIdle()
        content?.fling(Direction.UP)
        device.waitForIdle()
        content?.scroll(Direction.DOWN, 0.5f)
        device.waitForIdle()
        content?.pinchOpen(0.8f, 30)
        device.waitForIdle()
        content?.pinchClose(0.8f, 30)
        device.waitForIdle()
    }
}
