package app.terbaca.core.model

/**
 * Extracted text and character bounding boxes for a PDF page in page coordinates.
 */
data class PageText(
    val pageIndex: Int,
    val text: String,
    val charBoxes: List<CharBox> = emptyList()
)
