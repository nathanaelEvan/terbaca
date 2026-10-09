package app.terbaca.core.model

/**
 * Represents a document outline bookmark (table of contents item).
 */
data class OutlineItem(
    val title: String,
    val pageIndex: Int,
    val destX: Float? = null,
    val destY: Float? = null,
    val children: List<OutlineItem> = emptyList()
)
