package app.terbaca.feature.reader

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import app.terbaca.core.model.PageInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTransformStateTest {

    private val letterPage = PageInfo(index = 0, widthPoints = 612f, heightPoints = 792f)

    private class AutoAdvanceFrameClock : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test
    fun testUpdateViewport_calculatesCorrectScalesAndCenters() {
        val state = DocumentTransformState(DocumentLayout(listOf(letterPage)))
        // Viewport: 1224 x 1584 px (exactly 2.0x page dimensions)
        state.updateViewport(1224f, 1584f)

        assertEquals(2.0f, state.fitWidthScale, 0.001f)
        assertEquals(2.0f, state.fitPageScale, 0.001f)
        assertEquals(2.0f, state.minScale, 0.001f)
        assertEquals(16.0f, state.maxScale, 0.001f)
        assertEquals(2.0f, state.scale, 0.001f)

        // Content fits exactly into viewport -> offsets at 0
        assertEquals(0f, state.offsetX, 0.001f)
        assertEquals(0f, state.offsetY, 0.001f)
    }

    @Test
    fun testViewportTallerThanPage_centersVertically() {
        val state = DocumentTransformState(DocumentLayout(listOf(letterPage)))
        // Viewport width = 612 (scale 1.0), height = 1000 (taller than 792)
        state.updateViewport(612f, 1000f)

        assertEquals(1.0f, state.scale, 0.001f)
        assertEquals(0f, state.offsetX, 0.001f)
        // Expected Y offset: (1000 - 792) / 2 = 104
        assertEquals(104f, state.offsetY, 0.001f)
    }

    @Test
    fun testPan_clampsToBounds() {
        val state = DocumentTransformState(DocumentLayout(listOf(letterPage)))
        state.updateViewport(612f, 792f)
        // Zoom in 2x: content width = 1224, content height = 1584
        state.onPinch(2f, Offset(306f, 396f))
        assertEquals(2.0f, state.scale, 0.001f)

        // Offset should be clamped between (viewport - content) = (612 - 1224) = -612 and 0
        state.onPan(1000f, 1000f)
        assertEquals(0f, state.offsetX, 0.001f)
        assertEquals(0f, state.offsetY, 0.001f)

        state.onPan(-5000f, -5000f)
        assertEquals(-612f, state.offsetX, 0.001f)
        assertEquals(-792f, state.offsetY, 0.001f)
    }

    @Test
    fun testPinch_anchorsFocalPointAccurately() {
        val state = DocumentTransformState(DocumentLayout(listOf(letterPage)))
        state.updateViewport(612f, 792f)

        val focal = Offset(200f, 300f)
        // Point in page coordinates under focal before zoom:
        // Px = (focal.x - offsetX) / scale
        val pageXBefore = (focal.x - state.offsetX) / state.scale
        val pageYBefore = (focal.y - state.offsetY) / state.scale

        // Pinch zoom 1.5x
        state.onPinch(1.5f, focal)
        assertEquals(1.5f, state.scale, 0.001f)

        // Point in page coordinates under focal after zoom:
        val pageXAfter = (focal.x - state.offsetX) / state.scale
        val pageYAfter = (focal.y - state.offsetY) / state.scale

        // Focal point anchoring formula ensures the exact same page point stays under focal
        assertEquals("X point under focal must be invariant", pageXBefore, pageXAfter, 0.01f)
        assertEquals("Y point under focal must be invariant", pageYBefore, pageYAfter, 0.01f)
    }

    @Test
    fun testVisibleRectPoints_reflectsCurrentViewportAndScale() {
        val state = DocumentTransformState(DocumentLayout(listOf(letterPage)))
        state.updateViewport(612f, 792f)
        state.onPinch(2f, Offset(0f, 0f))
        // Pan left by 100px (moving viewport right by 100px)
        state.onPan(-100f, -200f)

        val rect = state.getVisibleRectPoints()
        // Offset = -100px at scale 2.0 -> left point = 50 pt
        assertEquals(50f, rect.left, 0.01f)
        // Offset = -200px at scale 2.0 -> top point = 100 pt
        assertEquals(100f, rect.top, 0.01f)
        // Viewport width = 612px -> right point = 50 + (612 / 2) = 356 pt
        assertEquals(356f, rect.right, 0.01f)
        // Viewport height = 792px -> bottom point = 100 + (792 / 2) = 496 pt
        assertEquals(496f, rect.bottom, 0.01f)
    }

    @Test
    fun testFling_updatesOffsetsWithDecay() = runTest {
        withContext(AutoAdvanceFrameClock()) {
            val state = DocumentTransformState(DocumentLayout(listOf(letterPage)))
            state.updateViewport(612f, 792f)
            state.onPinch(3f, Offset(306f, 396f))

            // Initial offset should be centered
            val initialX = state.offsetX
            val initialY = state.offsetY

            state.fling(-500f, -500f)

            assertTrue("Fling must shift offsetX to the left", state.offsetX < initialX)
            assertTrue("Fling must shift offsetY upwards", state.offsetY < initialY)
        }
    }

    @Test
    fun levelScale_staysAtPinchStartUntilZoomEnds() {
        val state = DocumentTransformState(DocumentLayout(listOf(letterPage)))
        state.updateViewport(612f, 792f)
        assertEquals(1f, state.levelScale, 0.001f)

        state.beginZoom()
        state.onPinch(1.5f, Offset(306f, 396f))
        state.beginZoom() // later pinch events don't move the frozen scale
        state.onPinch(2f, Offset(306f, 396f))
        assertEquals(3f, state.scale, 0.001f)
        assertEquals(1f, state.levelScale, 0.001f)

        state.endZoom()
        assertEquals(3f, state.levelScale, 0.001f)
    }

    @Test
    fun testInitialPage_restoresReadingPosition() {
        val pages = listOf(
            PageInfo(index = 0, widthPoints = 612f, heightPoints = 792f),
            PageInfo(index = 1, widthPoints = 612f, heightPoints = 792f),
            PageInfo(index = 2, widthPoints = 612f, heightPoints = 792f)
        )
        val layout = DocumentLayout(pages)
        val state = DocumentTransformState(layout, initialPage = 1)
        state.updateViewport(612f, 792f)

        val expectedOffsetY = -layout.pageRect(1).top * state.scale
        assertEquals(expectedOffsetY, state.offsetY, 0.001f)
        assertEquals(1, state.currentPage)
    }

    @Test
    fun initialScale_isFitWidth_whenFitWidthBelowOne() {
        // A large-format page (A1-ish) in a smaller viewport: fit-width is 0.5 px/pt.
        val poster = PageInfo(index = 0, widthPoints = 2000f, heightPoints = 3000f)
        val state = DocumentTransformState(DocumentLayout(listOf(poster)))
        state.updateViewport(1000f, 800f)

        assertEquals(0.5f, state.fitWidthScale, 0.0001f)
        assertEquals(0.5f, state.scale, 0.0001f)
    }

    @Test
    fun resize_atFitWidth_refitsAndKeepsReadingPosition() {
        val pages = List(5) { PageInfo(index = it, widthPoints = 612f, heightPoints = 792f) }
        val layout = DocumentLayout(pages)
        val state = DocumentTransformState(layout)
        state.updateViewport(1224f, 1000f) // fit-width 2
        state.onPan(0f, -(layout.pageRect(2).top + 100f) * 2f) // 100 pt into page 3
        val anchor = -state.offsetY / state.scale

        // Docking the sidebar takes 240 px off the width.
        state.updateViewport(984f, 1000f)

        assertEquals(984f / 612f, state.scale, 0.0001f)
        assertEquals("no horizontal crop", 0f, state.offsetX, 0.001f)
        assertEquals("document point at the top stays", anchor, -state.offsetY / state.scale, 0.01f)

        // And back, as on closing the sidebar.
        state.updateViewport(1224f, 1000f)
        assertEquals(2f, state.scale, 0.0001f)
        assertEquals(anchor, -state.offsetY / state.scale, 0.01f)
    }

    @Test
    fun resize_whenZoomedIn_keepsScale() {
        val state = DocumentTransformState(DocumentLayout(listOf(letterPage, letterPage.copy(index = 1))))
        state.updateViewport(612f, 792f)
        state.onPinch(2f, Offset(0f, 0f))

        state.updateViewport(500f, 792f)

        assertEquals(2f, state.scale, 0.0001f)
    }

    @Test
    fun currentPage_shortPages_reachesFirstAndLast() {
        // 20 pages of 100 pt in a 792 pt viewport: the centre never reaches page 1 or 20.
        val pages = List(20) { PageInfo(index = it, widthPoints = 612f, heightPoints = 100f) }
        val state = DocumentTransformState(DocumentLayout(pages))
        state.updateViewport(612f, 792f)

        assertEquals(0, state.currentPage)
        state.scrollToBottom()
        assertEquals(19, state.currentPage)
        state.scrollToTop()
        assertEquals(0, state.currentPage)
    }

    @Test
    fun currentPage_midScroll_isPageWithLargestVisibleShare() {
        // Page slots: 0 [0,800) 1 [808,858) 2 [866,1666) 3 [1674,2474); heights 800, 50, 800, 800.
        val pages = listOf(800f, 50f, 800f, 800f).mapIndexed { i, h ->
            PageInfo(index = i, widthPoints = 612f, heightPoints = h)
        }
        val state = DocumentTransformState(DocumentLayout(pages))
        state.updateViewport(612f, 600f)

        // View [500, 1100): page 0 shows 300 pt, page 1 50 pt, page 2 234 pt.
        state.onPan(0f, -500f)
        assertEquals(0, state.currentPage)

        // View [700, 1300): page 0 100 pt, page 1 50 pt, page 2 434 pt; the centre (1000) agrees.
        state.onPan(0f, -200f)
        assertEquals(2, state.currentPage)

        // View [1300, 1900): page 2 366 pt, page 3 226 pt.
        state.onPan(0f, -600f)
        assertEquals(2, state.currentPage)

        // View [1500, 2100): page 2 166 pt, page 3 426 pt.
        state.onPan(0f, -200f)
        assertEquals(3, state.currentPage)
    }

    @Test
    fun keyboardAction_cancelsRunningFling() = runTest {
        val pages = List(10) { PageInfo(index = it, widthPoints = 612f, heightPoints = 792f) }
        val state = DocumentTransformState(DocumentLayout(pages))
        state.updateViewport(612f, 792f)

        val flingJob = launch(DelayFrameClock()) { state.fling(0f, -20_000f) }
        advanceTimeBy(100)
        assertTrue("fling is moving the document", state.offsetY < 0f)

        state.scrollToTop()
        advanceUntilIdle()

        assertTrue(flingJob.isCancelled)
        assertEquals("the fling must not overwrite Home", 0f, state.offsetY, 0.001f)
    }

    /** A frame every 16 ms of virtual time, so a fling stays in flight between test steps. */
    private class DelayFrameClock : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(16)
            time += 16_000_000L
            return onFrame(time)
        }
    }
}
