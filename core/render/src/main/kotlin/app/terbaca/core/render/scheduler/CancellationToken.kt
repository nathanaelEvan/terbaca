package app.terbaca.core.render.scheduler

/**
 * Functional interface for checking progressive rendering cancellation status
 * per PRD §4.5 ("IFSDK_PAUSE callback that checks a cancel flag").
 */
fun interface CancellationToken {
    fun isCancelled(): Boolean
}
