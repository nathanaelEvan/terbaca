package app.terbaca.feature.reader

import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.pyramid.ViewportRect

/** Gap between consecutive pages, in PDF points (scales with zoom, like the pages). */
const val PAGE_GAP_POINTS = 8f

/**
 * Continuous vertical layout (F-MODE-1): pages stacked top to bottom, each centered horizontally
 * in a column as wide as the widest page. All coordinates are document points, so one scale and
 * one offset map the whole document to the screen.
 */
class DocumentLayout(
    val pages: List<PageInfo>,
    gapPoints: Float = PAGE_GAP_POINTS
) {
    init {
        require(pages.isNotEmpty()) { "Document has no pages" }
    }

    val width: Float = pages.maxOf { it.widthPoints }
    val maxPageHeight: Float = pages.maxOf { it.heightPoints }

    private val tops = FloatArray(pages.size)
    val height: Float

    init {
        var y = 0f
        for (i in pages.indices) {
            tops[i] = y
            y += pages[i].heightPoints + if (i < pages.lastIndex) gapPoints else 0f
        }
        height = y
    }

    val pageCount: Int get() = pages.size

    /** Page [index]'s rectangle in document points. */
    fun pageRect(index: Int): ViewportRect {
        val page = pages[index]
        val left = (width - page.widthPoints) / 2f
        return ViewportRect(left, tops[index], left + page.widthPoints, tops[index] + page.heightPoints)
    }

    /** Page whose slot contains document y [y]; the gap below a page belongs to that page. */
    fun pageAt(y: Float): Int {
        var lo = 0
        var hi = pages.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Pages that intersect document-point rect [rect] vertically, or an empty range. */
    fun pagesIn(rect: ViewportRect): IntRange {
        if (rect.bottom <= 0f || rect.top >= height) return IntRange.EMPTY
        val first = pageAt(rect.top).let { if (tops[it] + pages[it].heightPoints <= rect.top) it + 1 else it }
        val last = pageAt(rect.bottom).let { if (tops[it] >= rect.bottom) it - 1 else it }
        return if (first > last) IntRange.EMPTY else first..last
    }
}
