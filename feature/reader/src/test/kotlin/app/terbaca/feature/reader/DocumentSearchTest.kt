package app.terbaca.feature.reader

import app.terbaca.core.model.CharBox
import app.terbaca.core.model.PageRect
import app.terbaca.core.model.PageText
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSearchTest {

    @Test
    fun findMatches_isCaseInsensitiveAndNonOverlapping() {
        assertEquals(listOf(0, 10), findMatches("Attention attention", "attention"))
        assertEquals(listOf(0, 10), findMatches("Attention attention", "ATTENTION"))
        assertEquals(listOf(0, 2), findMatches("aaaa", "aa"))
        assertTrue(findMatches("abc", "").isEmpty())
    }

    @Test
    fun matchRects_mergesPerLineAndSkipsEmptyBoxes() {
        // "ab" on line 1, a zero box (line break), "cd" on line 2.
        val boxes = listOf(
            CharBox(10f, 100f, 20f, 112f),
            CharBox(20f, 101f, 30f, 112f),
            CharBox(0f, 0f, 0f, 0f),
            CharBox(10f, 120f, 20f, 132f),
            CharBox(20f, 120f, 30f, 132f)
        )
        val rects = matchRects(boxes, 0, 5)
        assertEquals(listOf(PageRect(10f, 100f, 30f, 112f), PageRect(10f, 120f, 30f, 132f)), rects)
    }

    @Test
    fun matchRects_pastEndOfBoxes_isTruncated() {
        val boxes = listOf(CharBox(10f, 100f, 20f, 112f))
        assertEquals(1, matchRects(boxes, 0, 3).size)
    }

    @Test
    fun excerptOf_putsMatchOnOneLineWithEllipses() {
        val text = "x".repeat(50) + "\r\nself-attention\r\nlayers" + "y".repeat(50)
        val start = text.indexOf("attention")
        val (excerpt, matchStart) = excerptOf(text, start, "attention".length)
        assertTrue(excerpt.startsWith("…"))
        assertTrue(excerpt.endsWith("…"))
        assertEquals("attention", excerpt.substring(matchStart, matchStart + "attention".length))
        assertTrue(!excerpt.contains('\n'))
    }

    @Test
    fun searchDocument_emitsPerPageInOrderAndSkipsUnreadablePages() = runTest {
        val texts = mapOf(
            0 to PageText(0, "Transformer models"),
            2 to PageText(2, "the transformer, a transformer")
        )
        val emitted = searchDocument(3, " transformer ") { texts[it] }.toList()
        assertEquals(listOf(listOf(0), listOf(2, 2)), emitted.map { hits -> hits.map { it.pageIndex } })
    }

    @Test
    fun searchDocument_shortQuery_searchesNothing() = runTest {
        var loads = 0
        val emitted = searchDocument(3, "a") { loads++; PageText(it, "aaa") }.toList()
        assertTrue(emitted.isEmpty())
        assertEquals(0, loads)
    }
}
