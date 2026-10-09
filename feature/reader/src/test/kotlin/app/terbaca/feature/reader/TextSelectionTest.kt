package app.terbaca.feature.reader

import app.terbaca.core.model.CharBox
import app.terbaca.core.model.PageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSelectionTest {

    // "ab cd" on one line, then "ef" on the next; the space and line break have empty boxes.
    private val text = "ab cd\r\nef"
    private val boxes = listOf(
        CharBox(10f, 100f, 20f, 112f), CharBox(20f, 100f, 30f, 112f),
        CharBox(0f, 0f, 0f, 0f),
        CharBox(40f, 100f, 50f, 112f), CharBox(50f, 100f, 60f, 112f),
        CharBox(0f, 0f, 0f, 0f), CharBox(0f, 0f, 0f, 0f),
        CharBox(10f, 120f, 20f, 132f), CharBox(20f, 120f, 30f, 132f)
    )
    private val page = PageText(0, text, boxes)

    @Test
    fun charIndexAt_insideBox_returnsThatChar() {
        assertEquals(3, charIndexAt(boxes, 45f, 105f))
    }

    @Test
    fun charIndexAt_besideLine_prefersSameLine() {
        // Right of "cd" on line 1, closer vertically to line 1 than to "ef".
        assertEquals(4, charIndexAt(boxes, 90f, 106f))
        assertEquals(8, charIndexAt(boxes, 90f, 126f))
    }

    @Test
    fun charIndexAt_noBoxes_isNull() {
        assertNull(charIndexAt(emptyList(), 1f, 1f))
    }

    @Test
    fun wordAt_expandsToWordAndTrimsGluePunctuation() {
        assertEquals(3..4, wordAt(text, 4))
        assertEquals(3..6, wordAt("a -self- b", 5))
        assertEquals(0..4, wordAt("don't x", 2))
        assertTrue(wordAt(text, 99).isEmpty())
    }

    @Test
    fun selectedText_normalisesLineEnds() {
        assertEquals("cd\nef", selectedText(text, 3, 9))
        assertEquals("", selectedText(text, 5, 5))
    }

    @Test
    fun moveHandle_extendsAndSwapsWithoutInverting() {
        val word = TextSelection(page, 3, 5) // "cd"
        assertEquals("cd\nef", moveHandle(word, movingStart = false, index = 8).text)
        assertEquals("ab cd", moveHandle(word, movingStart = true, index = 0).text)
        // End handle dragged before the start: the start becomes the end.
        assertEquals("ab c", moveHandle(word, movingStart = false, index = 0).text)
    }
}
