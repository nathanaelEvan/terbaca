package app.terbaca.feature.reader

import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.pyramid.ViewportRect

/** Gap between consecutive pages, in PDF points (scales with zoom, like the pages). */
const val PAGE_GAP_POINTS = 8f

/**
 * Continuous vertical layout (F-MODE-1): rows stacked top to bottom, each centered horizontally
 * in a column as wide as the widest row. A row holds one page, or two side by side when [spread]
 * is on (F-MODE-2: two-page spread, with the first page alone when [coverAlone]). All coordinates
 * are document points, so one scale and one offset map the whole document to the screen.
 */
class DocumentLayout(
    val pages: List<PageInfo>,
    private val gapPoints: Float = PAGE_GAP_POINTS,
    val spread: Boolean = false,
    val coverAlone: Boolean = true
) {
    init {
        require(pages.isNotEmpty()) { "Document has no pages" }
    }

    /** Page index ranges, one per row, in reading order. */
    private val rows: List<IntRange> = buildList {
        var i = 0
        if (spread && coverAlone) {
            add(0..0)
            i = 1
        }
        while (i < pages.size) {
            val last = if (spread) minOf(i + 1, pages.lastIndex) else i
            add(i..last)
            i = last + 1
        }
    }

    private val rowOfPage = IntArray(pages.size).also { rowOf ->
        rows.forEachIndexed { r, range -> for (i in range) rowOf[i] = r }
    }

    private fun rowWidth(row: IntRange): Float =
        row.sumOf { pages[it].widthPoints.toDouble() }.toFloat() + gapPoints * (row.count() - 1)

    private fun rowHeight(row: IntRange): Float = row.maxOf { pages[it].heightPoints }

    val width: Float = rows.maxOf { rowWidth(it) }
    val maxPageHeight: Float = rows.maxOf { rowHeight(it) }

    private val rowTops = FloatArray(rows.size)
    val height: Float

    init {
        var y = 0f
        for (r in rows.indices) {
            rowTops[r] = y
            y += rowHeight(rows[r]) + if (r < rows.lastIndex) gapPoints else 0f
        }
        height = y
    }

    val pageCount: Int get() = pages.size

    /** Page [index]'s rectangle in document points. */
    fun pageRect(index: Int): ViewportRect {
        val r = rowOfPage[index]
        val row = rows[r]
        var left = (width - rowWidth(row)) / 2f
        for (i in row.first until index) left += pages[i].widthPoints + gapPoints
        val page = pages[index]
        return ViewportRect(left, rowTops[r], left + page.widthPoints, rowTops[r] + page.heightPoints)
    }

    /** First page of the row whose slot contains document y [y]; the gap below a row belongs to it. */
    fun pageAt(y: Float): Int = rows[rowAt(y)].first

    /** Pages of the row whose slot contains document y [y]. */
    fun rowPagesAt(y: Float): IntRange = rows[rowAt(y)]

    private fun rowAt(y: Float): Int {
        var lo = 0
        var hi = rows.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (rowTops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Pages that intersect document-point rect [rect] vertically, or an empty range. */
    fun pagesIn(rect: ViewportRect): IntRange {
        if (rect.bottom <= 0f || rect.top >= height) return IntRange.EMPTY
        val first = rowAt(rect.top).let { if (rowTops[it] + rowHeight(rows[it]) <= rect.top) it + 1 else it }
        val last = rowAt(rect.bottom).let { if (rowTops[it] >= rect.bottom) it - 1 else it }
        return if (first > last) IntRange.EMPTY else rows[first].first..rows[last].last
    }
}
