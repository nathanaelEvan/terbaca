package app.terbaca.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val FLIPS = 8
private const val FLIP_STEPS = 8 // ~40 ms swipe: a quick flick, like flipping a slide by hand
private const val FLIP_SETTLE_MS = 500L
private const val SLIDE_SHARP_ON_ARRIVAL = "TerbacaSlideSharpOnArrival"
private const val SLIDE_NOT_SHARP_ON_ARRIVAL = "TerbacaSlideNotSharpOnArrival"

@OptIn(ExperimentalMetricApi::class)
private class SlideSharpOnArrivalMetric : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session
    ): List<Measurement> {
        val pkg = captureInfo.targetPackageName
        val sharpCount = traceSession.query(
            """
            SELECT count(*) as count FROM slice s
            JOIN process_track pt ON s.track_id = pt.id
            JOIN process p ON pt.upid = p.upid
            WHERE s.name = '$SLIDE_SHARP_ON_ARRIVAL' AND p.name LIKE '$pkg%'
            """.trimIndent()
        ).firstOrNull()?.long("count") ?: 0L
        val notSharpCount = traceSession.query(
            """
            SELECT count(*) as count FROM slice s
            JOIN process_track pt ON s.track_id = pt.id
            JOIN process p ON pt.upid = p.upid
            WHERE s.name = '$SLIDE_NOT_SHARP_ON_ARRIVAL' AND p.name LIKE '$pkg%'
            """.trimIndent()
        ).firstOrNull()?.long("count") ?: 0L
        return listOf(
            Measurement("slideSharpOnArrivalCount", sharpCount.toDouble()),
            Measurement("slideNotSharpOnArrivalCount", notSharpCount.toDouble())
        )
    }
}

/**
 * Slide flip on the 16:9 deck (corpus 5), which opens in slide mode (PRD §7: no frame over
 * 8.33 ms, next slide sharp on arrival). Flicks forward, then back, one slide at a time.
 */
@RunWith(AndroidJUnit4::class)
class SlideFlipBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun slideFlipBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), SlideSharpOnArrivalMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait { it.putExtra(EXTRA_CORPUS_FILE, "corpus/deck_16_9.pdf") }
            Thread.sleep(1_500) // let the first slide and its neighbours render before measuring
        }
    ) {
        val w = device.displayWidth
        val cy = device.displayHeight / 2
        val left = w / 5
        val right = w * 4 / 5
        repeat(FLIPS) {
            device.swipe(right, cy, left, cy, FLIP_STEPS)
            Thread.sleep(FLIP_SETTLE_MS)
        }
        repeat(FLIPS) {
            device.swipe(left, cy, right, cy, FLIP_STEPS)
            Thread.sleep(FLIP_SETTLE_MS)
        }
    }
}
