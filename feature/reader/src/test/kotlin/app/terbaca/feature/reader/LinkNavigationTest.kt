package app.terbaca.feature.reader

import androidx.compose.ui.geometry.Offset
import app.terbaca.core.model.PageInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkNavigationTest {

    private val pages = List(5) { PageInfo(index = it, widthPoints = 600f, heightPoints = 800f) }

    /** 1200 px wide viewport: fit-width scale 2, pages 1600 px tall plus 16 px gaps. */
    private fun state() = DocumentTransformState(DocumentLayout(pages)).apply { updateViewport(1200f, 1000f) }

    @Test
    fun scrollToDestination_withoutY_putsPageTopAtViewportTop() = runTest {
        val state = state()
        state.scrollToDestination(2)
        assertEquals(-(2 * 808f) * 2f, state.offsetY, 0.01f)
        assertEquals(2, state.currentPage)
    }

    @Test
    fun scrollToDestination_withY_putsDestinationJustBelowTop() = runTest {
        val state = state()
        state.scrollToDestination(1, pageY = 400f)
        // Page 1 starts at 808 pt; destination 400 pt down, minus the 12 pt margin.
        assertEquals(-(808f + 400f - 12f) * 2f, state.offsetY, 0.01f)
    }

    @Test
    fun scrollToDestination_withNaNY_fallsBackToPageTop() = runTest {
        val state = state()
        state.scrollToDestination(3, pageY = Float.NaN)
        assertEquals(-(3 * 808f) * 2f, state.offsetY, 0.01f)
    }

    @Test
    fun scrollToDestination_outOfRangePage_isClamped() = runTest {
        val state = state()
        state.scrollToDestination(99)
        assertEquals(4, state.currentPage)
    }

    @Test
    fun restoreViewport_returnsToSavedPosition() = runTest {
        val state = state()
        state.scrollToDestination(1, pageY = 100f)
        val saved = state.viewport
        state.scrollToDestination(4)
        state.restoreViewport(saved)
        assertEquals(saved.y, state.viewport.y, 0.01f)
        assertEquals(saved.zoom, state.viewport.zoom, 0.001f)
    }

    @Test
    fun pagePointAt_mapsScreenToPagePoints() = runTest {
        val state = state()
        state.scrollToDestination(1)
        // Screen (200, 300) px at scale 2 = document (100, 808 + 150) = page 1 point (100, 150).
        val point = state.pagePointAt(Offset(200f, 300f))!!
        assertEquals(1, point.pageIndex)
        assertEquals(100f, point.x, 0.01f)
        assertEquals(150f, point.y, 0.01f)
    }

    @Test
    fun pagePointAt_inGapBetweenPages_isNull() {
        val state = state()
        // Document y 804 pt (in the 8 pt gap below page 0) = 1608 px with offset 0.
        state.onPan(0f, -1000f)
        assertNull(state.pagePointAt(Offset(200f, 1608f - 1000f)))
    }

    @Test
    fun jumpHistory_isLastInFirstOutAndBounded() {
        val history = JumpHistory(capacity = 2)
        val a = JumpEntry(ReaderViewport(1f, 0f, 0f), 0)
        val b = JumpEntry(ReaderViewport(1f, 0f, 800f), 1)
        val c = JumpEntry(ReaderViewport(1f, 0f, 1600f), 2)
        history.push(a)
        history.push(b)
        history.push(b)
        history.push(c)
        assertEquals(c, history.pop())
        assertEquals(b, history.pop())
        assertNull(history.pop())
        assertNull(history.latest)
    }
}
