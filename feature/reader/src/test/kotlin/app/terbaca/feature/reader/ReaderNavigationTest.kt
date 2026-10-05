package app.terbaca.feature.reader

import app.terbaca.core.model.PageInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderNavigationTest {

    // Ten 600x800 pt pages; at viewport width 1200 px the scale is 2 px/pt.
    private val layout = DocumentLayout(List(10) { PageInfo(it, 600f, 800f) }, gapPoints = 10f)

    private fun state() = DocumentTransformState(layout).apply { updateViewport(1200f, 1000f) }

    @Test
    fun currentPage_isPageUnderViewportCenter() {
        val state = state()
        assertEquals(0, state.currentPage)
        // Center at y = (500 + 1700) / 2 = 1100 pt -> page 1 (starts at 810 pt).
        state.onPan(0f, -1700f)
        assertEquals(1, state.currentPage)
    }

    @Test
    fun scrollToPage_putsPageTopAtViewportTop() = runTest {
        val state = state()
        state.scrollToPage(3)
        assertEquals(-3 * 810f * 2f, state.offsetY, 0.01f)
        assertEquals(3, state.currentPage)
    }

    @Test
    fun scrollToPage_outOfRangeGoesToLastPage() = runTest {
        val state = state()
        state.scrollToPage(99)
        assertEquals(-9 * 810f * 2f, state.offsetY, 0.01f)
        assertEquals(9, state.currentPage)
    }

    @Test
    fun scrollToPage_clampsWhenPageTopCannotReachViewportTop() = runTest {
        // Viewport taller than the last page: its top can't reach the top of the screen.
        val state = DocumentTransformState(layout).apply { updateViewport(1200f, 2000f) }
        state.scrollToPage(9)
        assertEquals(2000f - layout.height * 2f, state.offsetY, 0.01f)
        assertEquals(1f, state.scrollFraction, 0.0001f)
    }

    @Test
    fun scrollFraction_roundTripsThroughScrollToFraction() {
        val state = state()
        assertEquals(0f, state.scrollFraction, 0f)
        state.scrollToFraction(0.25f)
        assertEquals(0.25f, state.scrollFraction, 0.0001f)
        state.scrollToFraction(2f)
        assertEquals(1f, state.scrollFraction, 0.0001f)
    }

    @Test
    fun scrollFraction_isZeroWhenDocumentFits() {
        val state = DocumentTransformState(DocumentLayout(listOf(PageInfo(0, 600f, 800f))))
        state.updateViewport(600f, 2000f)
        assertEquals(0f, state.scrollFraction, 0f)
        state.scrollToFraction(0.5f)
        assertEquals(0f, state.scrollFraction, 0f)
    }

    @Test
    fun parsePageNumber_acceptsOnlyPagesInRange() {
        assertEquals(1, parsePageNumber("1", 30))
        assertEquals(30, parsePageNumber(" 30 ", 30))
        assertNull(parsePageNumber("0", 30))
        assertNull(parsePageNumber("31", 30))
        assertNull(parsePageNumber("", 30))
        assertNull(parsePageNumber("abc", 30))
    }
}
