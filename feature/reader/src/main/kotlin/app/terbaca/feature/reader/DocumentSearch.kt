package app.terbaca.feature.reader

import app.terbaca.core.model.CharBox
import app.terbaca.core.model.PageRect
import app.terbaca.core.model.PageText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.max
import kotlin.math.min

/**
 * One match of a search (F-ACAD-5): its page, its position in the page text, a one-line
 * [excerpt] with the match at [excerptMatchStart], and its rectangles in page points (one per
 * line the match spans) for highlights.
 */
data class SearchHit(
    val pageIndex: Int,
    val start: Int,
    val length: Int,
    val excerpt: String,
    val excerptMatchStart: Int,
    val rects: List<PageRect>
)

/** Queries shorter than this aren't searched: one letter matches nearly every line. */
const val MIN_QUERY_LENGTH = 2

private const val EXCERPT_CONTEXT_CHARS = 40
private val WHITESPACE = Regex("\\s+")

/** Start indices of the non-overlapping, case-insensitive matches of [query] in [text]. */
internal fun findMatches(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    val starts = ArrayList<Int>()
    var from = 0
    while (true) {
        val i = text.indexOf(query, from, ignoreCase = true)
        if (i < 0) break
        starts.add(i)
        from = i + query.length
    }
    return starts
}

/**
 * Rectangles of the characters [start] until [start] + [length], merged into one per line.
 * Characters PDFium gave no box (all zero) are skipped.
 */
internal fun matchRects(boxes: List<CharBox>, start: Int, length: Int): List<PageRect> {
    val rects = ArrayList<PageRect>()
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
    var open = false
    for (i in start until min(boxes.size, start + length)) {
        val b = boxes[i]
        if (b.right <= b.left || b.bottom <= b.top) continue
        val sameLine = open && min(bottom, b.bottom) - max(top, b.top) > min(bottom - top, b.bottom - b.top) / 2
        if (sameLine) {
            left = min(left, b.left)
            top = min(top, b.top)
            right = max(right, b.right)
            bottom = max(bottom, b.bottom)
        } else {
            if (open) rects.add(PageRect(left, top, right, bottom))
            left = b.left
            top = b.top
            right = b.right
            bottom = b.bottom
            open = true
        }
    }
    if (open) rects.add(PageRect(left, top, right, bottom))
    return rects
}

/** The match with up to [EXCERPT_CONTEXT_CHARS] characters on each side, on one line. */
internal fun excerptOf(text: String, start: Int, length: Int): Pair<String, Int> {
    val from = max(0, start - EXCERPT_CONTEXT_CHARS)
    val to = min(text.length, start + length + EXCERPT_CONTEXT_CHARS)
    val before = (if (from > 0) "…" else "") + text.substring(from, start).replace(WHITESPACE, " ").trimStart()
    val match = text.substring(start, start + length).replace(WHITESPACE, " ")
    val after = text.substring(start + length, to).replace(WHITESPACE, " ").trimEnd() + if (to < text.length) "…" else ""
    return (before + match + after) to before.length
}

/** All hits of [query] in one page's text. */
internal fun searchPage(page: PageText, query: String): List<SearchHit> =
    findMatches(page.text, query).map { start ->
        val (excerpt, matchStart) = excerptOf(page.text, start, query.length)
        SearchHit(page.pageIndex, start, query.length, excerpt, matchStart, matchRects(page.charBoxes, start, query.length))
    }

/**
 * Searches [pageCount] pages in order, emitting each page's hits as soon as it is searched
 * (progressive results, F-ACAD-5). Pages whose text can't be loaded ([loadText] returns null)
 * are skipped. Collect it in a cancellable scope: a new query cancels the old search.
 */
fun searchDocument(
    pageCount: Int,
    query: String,
    loadText: suspend (Int) -> PageText?
): Flow<List<SearchHit>> = flow {
    val q = query.trim()
    if (q.length < MIN_QUERY_LENGTH) return@flow
    for (index in 0 until pageCount) {
        val text = loadText(index) ?: continue
        val hits = searchPage(text, q)
        if (hits.isNotEmpty()) emit(hits)
    }
}
