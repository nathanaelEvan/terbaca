package app.terbaca.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark measuring frame timing during continuous gestures against §7 budget
 * (>= 99% of frames within 6.94 ms at 144 Hz; zero frames over 16.6 ms).
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollCompilationBaselineProfiles() = benchmarkRule.measureRepeated(
        packageName = "app.terbaca",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.WARM
    ) {
        pressHome()
        startActivityAndWait()

        device.wait(Until.hasObject(By.pkg("app.terbaca")), 5_000)
        Thread.sleep(1000)

        val content = device.findObject(By.pkg("app.terbaca"))
        // Continuous fling up and down
        content?.fling(Direction.DOWN)
        device.waitForIdle()
        content?.fling(Direction.UP)
        device.waitForIdle()

        // Pan / scroll
        content?.scroll(Direction.DOWN, 0.5f)
        device.waitForIdle()
        content?.scroll(Direction.UP, 0.5f)
        device.waitForIdle()

        // Pinch zoom in and out
        content?.pinchOpen(0.8f, 30)
        device.waitForIdle()
        content?.pinchClose(0.8f, 30)
        device.waitForIdle()
    }
}
