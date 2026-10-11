package app.terbaca.core.render

import android.os.Trace

/**
 * Async trace sections for the PRD §7 latency budgets, read by the :benchmark module from a
 * Perfetto trace. A section only starts while tracing is on, so with tracing off every call,
 * and the per-frame checks its callers guard with [isSharpenPending] / [isOpenPending], is a
 * flag check. Main thread only.
 *
 * "Sharp" means the visible detail layer is fully opaque, its 150 ms crossfade included (PRD §7).
 */
object PerfTrace {
    /** From the request to open a document until its first page is sharp. */
    const val OPEN_TO_SHARP = "TerbacaOpenToSharp"

    /** From the end of a gesture (fingers up, fling stopped) until the visible area is sharp. */
    const val SHARPEN = "TerbacaSharpen"

    /** Marks a [SHARPEN] section cut short by a new touch before the view became sharp. */
    const val SHARPEN_CANCELLED = "TerbacaSharpenCancelled"

    /** Marks a slide flip where the arriving slide is already rendered and sharp (PRD §7). */
    const val SLIDE_SHARP_ON_ARRIVAL = "TerbacaSlideSharpOnArrival"

    /** Marks a slide flip where the arriving slide was not rendered and had to show a placeholder. */
    const val SLIDE_NOT_SHARP_ON_ARRIVAL = "TerbacaSlideNotSharpOnArrival"

    /** The platform trace calls; swapped in unit tests, where `android.os.Trace` is a stub. */
    internal interface Tracer {
        val isEnabled: Boolean
        fun beginAsync(name: String, cookie: Int)
        fun endAsync(name: String, cookie: Int)
        fun mark(name: String)
    }

    internal object SystemTracer : Tracer {
        override val isEnabled: Boolean get() = Trace.isEnabled()
        override fun beginAsync(name: String, cookie: Int) = Trace.beginAsyncSection(name, cookie)
        override fun endAsync(name: String, cookie: Int) = Trace.endAsyncSection(name, cookie)
        override fun mark(name: String) {
            Trace.beginSection(name)
            Trace.endSection()
        }
    }

    internal var tracer: Tracer = SystemTracer

    private var openCookie = 0
    private var sharpenCookie = 0

    /** True while an [OPEN_TO_SHARP] section is open. */
    var isOpenPending = false
        private set

    /** True while a [SHARPEN] section is open. */
    var isSharpenPending = false
        private set

    /** Starts an [OPEN_TO_SHARP] section; one still pending is left unfinished (see [abandonOpen]). */
    fun beginOpen() {
        if (!tracer.isEnabled) return
        isOpenPending = true
        tracer.beginAsync(OPEN_TO_SHARP, ++openCookie)
    }

    fun endOpen() {
        if (!isOpenPending) return
        isOpenPending = false
        tracer.endAsync(OPEN_TO_SHARP, openCookie)
    }

    /**
     * The open won't reach a sharp reader page (it failed, the deck opened in slide mode, or the
     * user left): leaves the section unfinished, which the benchmark ignores, so a later reader
     * frame can't close it with a duration that spans the detour.
     */
    fun abandonOpen() {
        isOpenPending = false
    }

    /** Starts a [SHARPEN] section; returns true when one is now pending. */
    fun beginSharpen(): Boolean {
        if (isSharpenPending) return true
        if (!tracer.isEnabled) return false
        isSharpenPending = true
        tracer.beginAsync(SHARPEN, ++sharpenCookie)
        return true
    }

    fun endSharpen() {
        if (!isSharpenPending) return
        isSharpenPending = false
        tracer.endAsync(SHARPEN, sharpenCookie)
    }

    /**
     * A new touch arrived, or the view went away, first: closes the section and marks it, so it
     * isn't read as a sample.
     */
    fun cancelSharpen() {
        if (!isSharpenPending) return
        endSharpen()
        tracer.mark(SHARPEN_CANCELLED)
    }

    /** Emits a point trace mark when tracing is enabled. */
    fun mark(name: String) {
        if (!tracer.isEnabled) return
        tracer.mark(name)
    }
}
