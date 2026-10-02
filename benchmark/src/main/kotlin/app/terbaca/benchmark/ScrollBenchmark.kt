package app.terbaca.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Frame timing during continuous pinch/pan/fling on corpus docs 1, 3 and 5 (PRD §7, M1 exit
 * criteria): >= 99% of frames within 6.94 ms at 144 Hz; zero frames over 16.6 ms.
 */
@RunWith(Parameterized::class)
class ScrollBenchmark(private val corpusFile: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun corpus() = listOf(
            "paper_two_column.pdf", // 1. two-column paper
            "heavy_plot.pdf", // 3. heavy vector plot
            "deck_16_9.pdf" // 5. 16:9 deck
        )
    }

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun gesturesBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait { it.putExtra(EXTRA_CORPUS_FILE, "corpus/$corpusFile") }
            Thread.sleep(1_500) // let the first page render sharp before measuring
        }
    ) {
        exerciseReader()
    }
}
