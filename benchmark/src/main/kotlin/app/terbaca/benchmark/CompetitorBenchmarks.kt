package app.terbaca.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Same gestures on Terbaca and on other PDF viewers (docs/competitor-benchmark.md). Run by
 * tools/perf/competitor/frames.sh, which passes the app and file as instrumentation arguments:
 * `competitor.package`, `competitor.component` (the app's VIEW activity) and `competitor.mediaId`
 * (the file's MediaStore id). Without them every test here is skipped.
 *
 * [CompilationMode.Ignore] leaves other apps' compiled code as installed: the benchmark never
 * resets or recompiles an app it doesn't own.
 */
@OptIn(ExperimentalMacrobenchmarkApi::class)
class CompetitorBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val args = InstrumentationRegistry.getArguments()
    private val pkg: String? = args.getString("competitor.package")
    private val component: String? = args.getString("competitor.component")
    private val mediaId: String? = args.getString("competitor.mediaId")

    private fun MacrobenchmarkScope.openDocument() {
        killProcess()
        pressHome()
        device.executeShellCommand(
            "am start -W -a android.intent.action.VIEW -t application/pdf --grant-read-uri-permission " +
                "-d content://media/external/file/$mediaId -n $component"
        )
        Thread.sleep(OPEN_SETTLE_MS)
    }

    /** Six identical flings down the document, then six back up. */
    @Test
    fun scrollFlings() {
        assumeTrue(pkg != null && component != null && mediaId != null)
        benchmarkRule.measureRepeated(
            packageName = pkg!!,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Ignore(),
            iterations = ITERATIONS,
            setupBlock = { openDocument() }
        ) {
            val cx = device.displayWidth / 2
            val cy = device.displayHeight / 2
            val dy = device.displayHeight / 4
            repeat(FLINGS) {
                device.swipe(cx, cy + dy, cx, cy - dy, FLING_STEPS)
                Thread.sleep(BETWEEN_FLINGS_MS)
            }
            repeat(FLINGS) {
                device.swipe(cx, cy - dy, cx, cy + dy, FLING_STEPS)
                Thread.sleep(BETWEEN_FLINGS_MS)
            }
        }
    }

    /** Pinch in and back out three times, at the same speed and size. */
    @Test
    fun pinchZoom() {
        assumeTrue(pkg != null && component != null && mediaId != null)
        benchmarkRule.measureRepeated(
            packageName = pkg!!,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Ignore(),
            iterations = ITERATIONS,
            setupBlock = { openDocument() }
        ) {
            repeat(PINCHES) {
                device.findObject(By.pkg(pkg!!).depth(0))?.pinchOpen(PINCH_PERCENT, PINCH_SPEED)
                Thread.sleep(AFTER_PINCH_MS)
                device.findObject(By.pkg(pkg!!).depth(0))?.pinchClose(PINCH_PERCENT, PINCH_SPEED)
                Thread.sleep(AFTER_PINCH_MS)
            }
        }
    }

    private companion object {
        const val ITERATIONS = 5
        const val FLINGS = 6
        const val FLING_STEPS = 6 // ~30 ms swipe → a fling with momentum, as in ReaderGestures
        const val BETWEEN_FLINGS_MS = 700L
        const val PINCHES = 3
        const val PINCH_PERCENT = 0.6f
        const val PINCH_SPEED = 2_500 // px/s, as in ReaderGestures
        const val AFTER_PINCH_MS = 1_200L
        const val OPEN_SETTLE_MS = 4_000L
    }
}
