package app.terbaca.core.model

import kotlin.math.abs
import kotlin.math.max

/**
 * Horizontal span of a detected text column in page points (top-left origin).
 */
data class ColumnRange(
    val left: Float,
    val right: Float
) {
    init {
        require(left <= right) { "left ($left) must be <= right ($right)" }
    }

    val width: Float get() = max(0f, right - left)
    val centerX: Float get() = (left + right) / 2f

    fun contains(x: Float): Boolean = x in left..right
}

/**
 * Vertical segment of a page with its detected column spans (PRD §6.4).
 * Single column indicates full-width content (title, abstract, wide figure).
 */
data class ColumnRegion(
    val top: Float,
    val bottom: Float,
    val columns: List<ColumnRange>
) {
    init {
        require(top <= bottom) { "top ($top) must be <= bottom ($bottom)" }
    }

    val height: Float get() = max(0f, bottom - top)
    val isMultiColumn: Boolean get() = columns.size > 1

    fun containsY(y: Float): Boolean = y in top..bottom
}

/**
 * Detected column structure for a single page (PRD §6.4, F-ACAD-1).
 */
data class PageColumnLayout(
    val pageIndex: Int,
    val regions: List<ColumnRegion>
) {
    /**
     * Returns the vertical region containing [y], or null if outside all regions.
     */
    fun regionAt(y: Float): ColumnRegion? =
        regions.firstOrNull { it.containsY(y) }

    /**
     * Returns the specific [ColumnRange] to zoom into when double-tapping at ([x], [y])
     * for F-ACAD-1. If the tapped region is full-width (single column or no columns),
     * returns null to signal fitting full page width. If multi-column, returns the
     * matching or closest column in that region.
     */
    fun findColumn(x: Float, y: Float): ColumnRange? {
        val region = regionAt(y) ?: return null
        if (!region.isMultiColumn) return null

        return region.columns.firstOrNull { it.contains(x) }
            ?: region.columns.minByOrNull { col ->
                when {
                    x < col.left -> col.left - x
                    x > col.right -> x - col.right
                    else -> 0f
                }
            }
    }
}
