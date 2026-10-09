package app.terbaca.feature.reader

import app.terbaca.core.model.CharBox
import app.terbaca.core.model.PageText
import kotlin.math.max
import kotlin.math.min

/**
 * A text selection on one page (F-ACAD-6): characters [start] until [end] (exclusive) of [page]'s
 * text.
 */
data class TextSelection(val page: PageText, val start: Int, val end: Int) {
    val text: String get() = selectedText(page.text, start, end)
}

/**
 * The index of the character at page point ([x], [y]): the box containing it, else the closest
 * box on the line under the point, else the closest box. Null when the page has no boxes.
 */
internal fun charIndexAt(boxes: List<CharBox>, x: Float, y: Float): Int? {
    var best = -1
    var bestScore = Float.MAX_VALUE
    for (i in boxes.indices) {
        val b = boxes[i]
        if (b.right <= b.left || b.bottom <= b.top) continue
        if (x in b.left..b.right && y in b.top..b.bottom) return i
        val dx = when {
            x < b.left -> b.left - x
            x > b.right -> x - b.right
            else -> 0f
        }
        val dy = when {
            y < b.top -> b.top - y
            y > b.bottom -> y - b.bottom
            else -> 0f
        }
        // Being on the same line matters more than being horizontally close.
        val score = dx + dy * LINE_WEIGHT
        if (score < bestScore) {
            bestScore = score
            best = i
        }
    }
    return best.takeIf { it >= 0 }
}

private const val LINE_WEIGHT = 8f

/** The word around [index] in [text] (letters, digits, and inner apostrophes or hyphens). */
internal fun wordAt(text: String, index: Int): IntRange {
    if (index !in text.indices) return IntRange.EMPTY
    fun isWord(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'
    if (!isWord(text[index])) return index..index
    var s = index
    var e = index
    while (s > 0 && isWord(text[s - 1])) s--
    while (e < text.lastIndex && isWord(text[e + 1])) e++
    // Trim punctuation that only glues words, so "-word'" selects "word".
    while (s < e && !text[s].isLetterOrDigit()) s++
    while (e > s && !text[e].isLetterOrDigit()) e--
    return s..e
}

/** The selected text, with PDFium's "\r\n" line ends as "\n". */
internal fun selectedText(text: String, start: Int, end: Int): String {
    val s = max(0, min(start, end))
    val e = min(text.length, max(start, end))
    return if (s >= e) "" else text.substring(s, e).replace("\r\n", "\n")
}

/**
 * The selection after moving one handle to character [index]: [movingStart] says which handle.
 * A handle dragged past the other swaps roles, so the selection never inverts.
 */
internal fun moveHandle(selection: TextSelection, movingStart: Boolean, index: Int): TextSelection {
    val fixed = if (movingStart) selection.end - 1 else selection.start
    val lo = min(fixed, index)
    val hi = max(fixed, index)
    return selection.copy(start = lo, end = hi + 1)
}
