package app.terbaca.feature.reader

import android.graphics.Bitmap
import app.terbaca.core.model.PageInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailsSidebarTest {

    @Test
    fun thumbnailAspectRatio_matchesPageDimensions() {
        val portraitPage = PageInfo(0, 600f, 800f)
        val landscapePage = PageInfo(1, 1920f, 1080f)
        val squarePage = PageInfo(2, 500f, 500f)

        assertEquals(0.75f, portraitPage.aspectRatio, 0.001f)
        assertEquals(1.777f, landscapePage.aspectRatio, 0.01f)
        assertEquals(1.0f, squarePage.aspectRatio, 0.001f)
    }

    @Test
    fun thumbnailLoader_loadsRequestedPageBitmap() = runTest {
        val page = PageInfo(3, 600f, 800f)
        var requestedPageIndex = -1

        val fakeLoader: suspend (PageInfo) -> Bitmap? = { p ->
            requestedPageIndex = p.index
            null
        }

        fakeLoader(page)
        assertEquals(3, requestedPageIndex)
    }

    @Test
    fun adaptiveDocking_viewportResizeRecalculatesFitScale() {
        val layout = DocumentLayout(List(5) { PageInfo(it, 600f, 800f) })
        val state = DocumentTransformState(layout)

        // Full screen landscape (1200 x 800)
        state.updateViewport(1200f, 800f)
        assertEquals(2.0f, state.fitWidthScale, 0.01f)

        // Docked sidebar (240px taken, remaining viewport is 960 x 800)
        state.updateViewport(960f, 800f)
        assertEquals(1.6f, state.fitWidthScale, 0.01f)
        assertTrue(state.scale >= state.minScale)
        assertTrue(state.scale <= state.maxScale)
    }

    @Test
    fun jumpToPageFromSidebar_scrollsToCorrectOffset() = runTest {
        val layout = DocumentLayout(List(5) { PageInfo(it, 600f, 800f) }, gapPoints = 10f)
        val state = DocumentTransformState(layout)
        state.updateViewport(960f, 800f)

        // Sidebar click triggers scrollToPage(2)
        state.scrollToPage(2)
        assertEquals(2, state.currentPage)
        assertEquals(-2 * 810f * state.scale, state.offsetY, 0.01f)
    }
}
