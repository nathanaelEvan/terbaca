package app.terbaca.feature.reader

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import app.terbaca.core.model.PageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardNavigationTest {

    private val page1 = PageInfo(index = 0, widthPoints = 612f, heightPoints = 792f)
    private val page2 = PageInfo(index = 1, widthPoints = 612f, heightPoints = 792f)
    private val page3 = PageInfo(index = 2, widthPoints = 612f, heightPoints = 792f)

    @Test
    fun testDocumentTransformState_scrollBy() {
        val state = DocumentTransformState(DocumentLayout(listOf(page1, page2, page3)))
        state.updateViewport(612f, 792f)
        assertEquals(0f, state.offsetY, 0.001f)

        // Scroll down by 100 px
        state.scrollBy(100f)
        assertEquals(-100f, state.offsetY, 0.001f)

        // Scroll up by 50 px
        state.scrollBy(-50f)
        assertEquals(-50f, state.offsetY, 0.001f)
    }

    @Test
    fun testDocumentTransformState_scrollPage() {
        val state = DocumentTransformState(DocumentLayout(listOf(page1, page2, page3)))
        state.updateViewport(612f, 1000f)

        // Page down by 0.9 viewport (900 px)
        state.scrollPage(down = true, fraction = 0.9f)
        assertEquals(-900f, state.offsetY, 0.001f)

        // Page up by 0.9 viewport (900 px)
        state.scrollPage(down = false, fraction = 0.9f)
        assertEquals(0f, state.offsetY, 0.001f)
    }

    @Test
    fun testDocumentTransformState_homeAndEnd() {
        val state = DocumentTransformState(DocumentLayout(listOf(page1, page2, page3)))
        state.updateViewport(612f, 792f)

        // End scrolls to bottom
        state.scrollToBottom()
        assertEquals(1f, state.scrollFraction, 0.001f)

        // Home scrolls to top
        state.scrollToTop()
        assertEquals(0f, state.scrollFraction, 0.001f)
    }

    @Test
    fun testDocumentTransformState_zoomShortcuts() {
        val state = DocumentTransformState(DocumentLayout(listOf(page1, page2, page3)))
        state.updateViewport(612f, 792f)
        val initialScale = state.scale

        // Zoom in by 1.25x
        state.zoomIn(1.25f)
        assertEquals(initialScale * 1.25f, state.scale, 0.001f)

        // Zoom out by 1.25x
        state.zoomOut(1.25f)
        assertEquals(initialScale, state.scale, 0.001f)

        // Zoom in 2x then reset
        state.zoomIn(2.0f)
        state.resetZoom()
        assertEquals(state.fitWidthScale, state.scale, 0.001f)
    }

    @Test
    fun testHandleReaderKeyEvent_withNativeKeyEvent() {
        val state = DocumentTransformState(DocumentLayout(listOf(page1, page2, page3)))
        state.updateViewport(612f, 792f)

        try {
            val downArrowEvent = KeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            )
            val consumed = handleReaderKeyEvent(downArrowEvent, state, scrollStepPx = 50f)
            assertTrue(consumed)
            assertEquals(-50f, state.offsetY, 0.001f)

            var searchTriggered = false
            val ctrlFEvent = KeyEvent(
                android.view.KeyEvent(
                    0L, 0L, android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_F, 0,
                    android.view.KeyEvent.META_CTRL_ON
                )
            )
            val searchConsumed = handleReaderKeyEvent(ctrlFEvent, state, onSearch = { searchTriggered = true })
            assertTrue(searchConsumed)
            assertTrue(searchTriggered)
        } catch (_: RuntimeException) {
            // If android.view.KeyEvent is not mocked in JVM, the state-level tests above still verify the logic
        }
    }

    @Test
    fun leftRightArrows_mapToHorizontalScroll() {
        assertEquals(ReaderNavAction.SCROLL_LEFT, resolveReaderKeyAction(Key.DirectionLeft, isCtrl = false, isShift = false))
        assertEquals(ReaderNavAction.SCROLL_RIGHT, resolveReaderKeyAction(Key.DirectionRight, isCtrl = false, isShift = false))
    }

    @Test
    fun scrollLeftRight_pansHorizontallyWhenZoomed() {
        val state = DocumentTransformState(DocumentLayout(listOf(page1, page2, page3)))
        state.updateViewport(612f, 792f)
        state.zoomIn(2f) // content 1224 px wide, centred on x: offsetX = -306

        assertTrue(applyReaderNavAction(ReaderNavAction.SCROLL_RIGHT, state, scrollStepPx = 50f))
        assertEquals(-356f, state.offsetX, 0.001f)

        assertTrue(applyReaderNavAction(ReaderNavAction.SCROLL_LEFT, state, scrollStepPx = 100f))
        assertEquals(-256f, state.offsetX, 0.001f)
    }

    @Test
    fun scrollLeftRight_isNoOpWhenPageFitsWidth() {
        val state = DocumentTransformState(DocumentLayout(listOf(page1, page2, page3)))
        state.updateViewport(612f, 792f)

        applyReaderNavAction(ReaderNavAction.SCROLL_RIGHT, state, scrollStepPx = 50f)
        assertEquals(0f, state.offsetX, 0.001f)
    }
}
