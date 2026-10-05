package app.terbaca.core.model

/**
 * Reading modes defined in PRD §3.4.
 */
enum class ReadingMode {
    /** Continuous vertical scrolling (F-MODE-1), default for portrait documents. */
    CONTINUOUS_VERTICAL,

    /** Slide mode (F-MODE-3), default for documents whose first page is landscape. */
    SLIDE
}

/**
 * Auto-detects reading mode from page geometry per PRD F-MODE-3 and F-MODE-5:
 * Default to slide mode if the first page is landscape; otherwise continuous vertical.
 */
fun autoDetectReadingMode(pages: List<PageInfo>): ReadingMode {
    val first = pages.firstOrNull() ?: return ReadingMode.CONTINUOUS_VERTICAL
    return if (first.isLandscape) ReadingMode.SLIDE else ReadingMode.CONTINUOUS_VERTICAL
}
