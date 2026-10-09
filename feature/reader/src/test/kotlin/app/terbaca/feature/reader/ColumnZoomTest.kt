package app.terbaca.feature.reader

import androidx.compose.runtime.MonotonicFrameClock
import app.terbaca.core.model.PageInfo
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class ColumnZoomTest {

    private val pages = List(3) { PageInfo(index = it, widthPoints = 600f, heightPoints = 800f) }

    /** 1200 px wide viewport: fit-width scale 2. */
    private fun state() = DocumentTransformState(DocumentLayout(pages)).apply { updateViewport(1200f, 1000f) }

    private class AutoAdvanceFrameClock : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test
    fun zoomToSpan_fitsColumnWidthPlusMargin() = runTest {
        val state = state()
        // Right column 320..560 pt with 8 pt margins = 256 pt across 1200 px.
        state.zoomToSpan(0, left = 320f, right = 560f, pageY = 400f, screenY = 500f, animated = false)
        val expectedScale = 1200f / 256f
        assertEquals(expectedScale, state.scale, 0.001f)
        assertEquals(-(320f - 8f) * expectedScale, state.offsetX, 0.01f)
    }

    @Test
    fun zoomToSpan_keepsTappedLineUnderFinger() = runTest {
        val state = state()
        state.scrollToDestination(1)
        state.zoomToSpan(1, left = 40f, right = 280f, pageY = 300f, screenY = 450f, animated = false)
        val point = state.pagePointAt(androidx.compose.ui.geometry.Offset(600f, 450f))!!
        assertEquals(1, point.pageIndex)
        assertEquals(300f, point.y, 0.01f)
    }

    @Test
    fun zoomToSpan_fullWidthWithoutMargin_isFitWidth() = runTest {
        val state = state()
        state.zoomToSpan(0, left = 320f, right = 560f, pageY = 100f, screenY = 100f, animated = false)
        state.zoomToSpan(0, left = 0f, right = 600f, pageY = 100f, screenY = 100f, marginPoints = 0f, animated = false)
        assertEquals(state.fitWidthScale, state.scale, 0.001f)
        assertEquals(0f, state.offsetX, 0.01f)
    }

    @Test
    fun zoomToSpan_animatedEndsAtSameTargetAndRestoreReturns() = runTest {
        val state = state()
        val before = state.viewport
        withContext(AutoAdvanceFrameClock()) {
            state.zoomToSpan(0, left = 40f, right = 280f, pageY = 400f, screenY = 500f)
        }
        assertEquals(1200f / 256f, state.scale, 0.001f)
        withContext(AutoAdvanceFrameClock()) {
            state.restoreViewport(before, animated = true)
        }
        assertEquals(before.zoom, state.viewport.zoom, 0.001f)
        assertEquals(before.y, state.viewport.y, 0.01f)
    }
}
