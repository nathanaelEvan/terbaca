package app.terbaca.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val FLIPS = 8
private const val FLIP_STEPS = 8 // ~40 ms swipe: a quick flick, like flipping a slide by hand
private const val FLIP_SETTLE_MS = 500L

/**
 * Slide flip on the 16:9 deck (corpus 5), which opens in slide mode (PRD §7: no frame over
 * 8.33 ms, next slide sharp on arrival). Flicks forward, then back, one slide at a time.
 */
@RunWith(AndroidJUnit4::class)
class SlideFlipBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun slideFlipBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
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
