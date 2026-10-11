package app.terbaca.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.traceprocessor.TraceProcessor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// Section names written by app.terbaca.core.render.PerfTrace (this module can't depend on it).
private const val OPEN_TO_SHARP = "TerbacaOpenToSharp"
private const val SHARPEN = "TerbacaSharpen"
private const val SHARPEN_CANCELLED = "TerbacaSharpenCancelled"

private const val NS_PER_MS = 1_000_000.0
private const val PAUSE_AFTER_GESTURE_MS = 1_500L
private const val SLOW_DRAG_STEPS = 60 // ~300 ms drag, little fling
private const val FLICK_STEPS = 6 // ~30 ms swipe, a fling over a few pages
private const val GESTURE_ROUNDS = 6
private const val ZOOM_PINCHES = 4
private const val PINCH_PERCENT = 0.4f
private const val PINCH_SPEED = 2_500

/**
 * Durations of the app's async trace section [section], in ms, as one sampled measurement
 * (reported as P50/P90/P95/P99 over all iterations), plus how many there were. Sections closed
 * by a new touch ([SHARPEN_CANCELLED] at their end) are counted apart and left out of the samples.
 */
@OptIn(ExperimentalMetricApi::class)
private class AsyncSectionMetric(private val section: String, private val label: String) : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session
    ): List<Measurement> {
        val pkg = captureInfo.targetPackageName
        val slices = traceSession.query(
            """
            SELECT s.ts AS ts, s.dur AS dur FROM slice s
            JOIN process_track pt ON s.track_id = pt.id
            JOIN process p ON pt.upid = p.upid
            WHERE s.name = '$section' AND p.name LIKE '$pkg%' AND s.dur > 0
            """.trimIndent()
        ).map { it.long("ts") to it.long("dur") }.toList()
        val cancelTimes = traceSession.query(
            """
            SELECT s.ts AS ts FROM slice s
            JOIN thread_track tt ON s.track_id = tt.id
            JOIN thread t USING (utid)
            JOIN process p USING (upid)
            WHERE s.name = '$SHARPEN_CANCELLED' AND p.name LIKE '$pkg%'
            """.trimIndent()
        ).map { it.long("ts") }.toList()

        // A cancelled section ends right before its marker; allow 2 ms between the two calls.
        val cancelled = slices.filter { (ts, dur) -> cancelTimes.any { it - (ts + dur) in 0..2_000_000 } }
        val samples = (slices - cancelled.toSet()).map { it.second / NS_PER_MS }
        return buildList {
            add(Measurement("${label}Count", samples.size.toDouble()))
            add(Measurement("${label}CancelledCount", cancelled.size.toDouble()))
            if (samples.isNotEmpty()) add(Measurement("${label}Ms", samples))
        }
    }
}

/**
 * Open a corpus document → first page sharp (PRD §7: 30-page paper ≤ 400 ms; heavy vector page
 * sharp within 1 s). Measured from the activity's request to open until every visible tile of
 * the first page is loaded. Includes copying the bundled file out of the APK's assets.
 */
@RunWith(Parameterized::class)
class OpenToSharpBenchmark(private val corpusFile: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun corpus() = listOf("paper_two_column.pdf", "heavy_plot.pdf")
    }

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun openToSharp() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(AsyncSectionMetric(OPEN_TO_SHARP, "openToSharp")),
        compilationMode = CompilationMode.Partial(),
        iterations = 10,
        startupMode = StartupMode.WARM
    ) {
        pressHome()
        startActivityAndWait { it.putExtra(EXTRA_CORPUS_FILE, "corpus/$corpusFile") }
        Thread.sleep(PAUSE_AFTER_GESTURE_MS) // the section ends once the page is sharp
    }
}

/**
 * Gesture end → visible area sharp (PRD §7: p95 ≤ 150 ms at fit width, ≤ 300 ms at 800%).
 * "pan": slow drags and flicks; "pinch": a zoom away and back. Each gesture is followed by a
 * pause long enough for the view to sharpen. At "max" the page is first pinched to the 8×
 * fit-width limit (800%). A pan usually ends sharp, because the fling's tail gives prefetched
 * tiles time to land; a pinch ends on a new tile level and re-renders the view.
 */
@RunWith(Parameterized::class)
class SharpenLatencyBenchmark(
    private val corpusFile: String,
    private val zoom: String,
    private val gesture: String
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}_{1}_{2}")
        fun cases() = listOf(
            arrayOf("paper_two_column.pdf", "fit", "pan"),
            arrayOf("paper_two_column.pdf", "fit", "pinch"),
            arrayOf("paper_two_column.pdf", "max", "pan"),
            arrayOf("paper_two_column.pdf", "max", "pinch"),
            arrayOf("heavy_plot.pdf", "fit", "pinch")
        )
    }

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun sharpen() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(AsyncSectionMetric(SHARPEN, "sharpen")),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait { it.putExtra(EXTRA_CORPUS_FILE, "corpus/$corpusFile") }
            Thread.sleep(PAUSE_AFTER_GESTURE_MS)
            if (zoom == "max") {
                repeat(ZOOM_PINCHES) {
                    readerRoot()?.pinchOpen(1f, PINCH_SPEED)
                    Thread.sleep(PAUSE_AFTER_GESTURE_MS)
                }
            }
        }
    ) {
        repeat(GESTURE_ROUNDS) { round ->
            if (gesture == "pan") pans(down = round % 2 == 0) else pinches()
        }
    }

    private fun MacrobenchmarkScope.pans(down: Boolean) {
        drag(down, SLOW_DRAG_STEPS)
        Thread.sleep(PAUSE_AFTER_GESTURE_MS)
        drag(down, FLICK_STEPS)
        Thread.sleep(PAUSE_AFTER_GESTURE_MS)
        if (zoom == "max") {
            // At fit width the page can't move sideways; the no-op drag would add free samples.
            dragSideways(down)
            Thread.sleep(PAUSE_AFTER_GESTURE_MS)
        }
    }

    /** Away from the start zoom and back: around fit width, in then out; at 800%, out then in. */
    private fun MacrobenchmarkScope.pinches() {
        if (zoom == "max") {
            readerRoot()?.pinchClose(PINCH_PERCENT, PINCH_SPEED)
        } else {
            readerRoot()?.pinchOpen(PINCH_PERCENT, PINCH_SPEED)
        }
        Thread.sleep(PAUSE_AFTER_GESTURE_MS)
        if (zoom == "max") {
            readerRoot()?.pinchOpen(1f, PINCH_SPEED)
        } else {
            readerRoot()?.pinchClose(PINCH_PERCENT, PINCH_SPEED)
        }
        Thread.sleep(PAUSE_AFTER_GESTURE_MS)
    }
}

/** A vertical drag over a third of the screen: content moves up when [down] (reading on). */
private fun MacrobenchmarkScope.drag(down: Boolean, steps: Int) {
    val cx = device.displayWidth / 2
    val top = device.displayHeight / 3
    val bottom = device.displayHeight * 2 / 3
    if (down) device.swipe(cx, bottom, cx, top, steps) else device.swipe(cx, top, cx, bottom, steps)
}

/** A slow horizontal drag, which only moves the view when zoomed past the screen width. */
private fun MacrobenchmarkScope.dragSideways(left: Boolean) {
    val cy = device.displayHeight / 2
    val l = device.displayWidth / 3
    val r = device.displayWidth * 2 / 3
    if (left) device.swipe(r, cy, l, cy, SLOW_DRAG_STEPS) else device.swipe(l, cy, r, cy, SLOW_DRAG_STEPS)
}
