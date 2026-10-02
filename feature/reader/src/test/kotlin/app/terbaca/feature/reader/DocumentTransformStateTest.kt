package app.terbaca.feature.reader

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import app.terbaca.core.model.PageInfo
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
}
