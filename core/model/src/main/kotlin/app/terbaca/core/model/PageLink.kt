package app.terbaca.core.model

/**
 * Hyperlink on a PDF page in page coordinates.
 *
 * Can represent an internal document jump (e.g. section, citation, table of contents)
 * or an external URI.
 */
data class PageLink(
    val sourceRect: PageRect,
    val targetPageIndex: Int = -1,
    val targetX: Float? = null,
    val targetY: Float? = null,
    val uri: String? = null
) {
    val isExternal: Boolean get() = !uri.isNullOrEmpty()
    val isInternal: Boolean get() = targetPageIndex >= 0
}
