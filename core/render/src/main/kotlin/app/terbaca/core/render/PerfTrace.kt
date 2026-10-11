package app.terbaca.core.render

import android.os.Trace

/**
 * Async trace sections for the PRD §7 latency budgets, read by the :benchmark module from a
 * Perfetto trace. With tracing off each call is a flag check. Main thread only.
 */
object PerfTrace {
    /** From the request to open a document until its first page is sharp. */
    const val OPEN_TO_SHARP = "TerbacaOpenToSharp"

    /** From the end of a gesture (fingers up, fling stopped) until the visible area is sharp. */
    const val SHARPEN = "TerbacaSharpen"

    /** Marks a [SHARPEN] section cut short by a new touch before the view became sharp. */
    const val SHARPEN_CANCELLED = "TerbacaSharpenCancelled"

    private var openCookie = 0
    private var openPending = false
    private var sharpenCookie = 0

    /** True while a [SHARPEN] section is open. */
    var isSharpenPending = false
        private set

    /**
     * Starts an [OPEN_TO_SHARP] section. One left open by a document that never showed the
     * reader (a deck opens in slide mode) stays unfinished, and the benchmark ignores it.
     */
    fun beginOpen() {
        openPending = true
        Trace.beginAsyncSection(OPEN_TO_SHARP, ++openCookie)
    }

    val isOpenPending: Boolean get() = openPending

    fun endOpen() {
        if (!openPending) return
        openPending = false
        Trace.endAsyncSection(OPEN_TO_SHARP, openCookie)
    }

    fun beginSharpen() {
        if (isSharpenPending) return
        isSharpenPending = true
        Trace.beginAsyncSection(SHARPEN, ++sharpenCookie)
    }

    fun endSharpen() {
        if (!isSharpenPending) return
        isSharpenPending = false
        Trace.endAsyncSection(SHARPEN, sharpenCookie)
    }

    /** A new touch arrived first: closes the section and marks it, so it isn't read as a sample. */
    fun cancelSharpen() {
        if (!isSharpenPending) return
        endSharpen()
        Trace.beginSection(SHARPEN_CANCELLED)
        Trace.endSection()
    }
}
