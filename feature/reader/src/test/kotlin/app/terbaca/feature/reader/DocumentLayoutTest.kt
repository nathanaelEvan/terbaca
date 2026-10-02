package app.terbaca.feature.reader

import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.pyramid.ViewportRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLayoutTest {

    private val gap = 10f

    // Page 1 is narrower and landscape, to check centering and mixed sizes.
    private val pages = listOf(
        PageInfo(0, 600f, 800f),
        PageInfo(1, 400f, 300f),
        PageInfo(2, 600f, 800f)
    )
    private val layout = DocumentLayout(pages, gapPoints = gap)

    @Test
    fun stacksPagesWithGapsAndCentersNarrowPages() {
        assertEquals(600f, layout.width, 0f)
        assertEquals(800f + gap + 300f + gap + 800f, layout.height, 0f)
        assertEquals(ViewportRect(0f, 0f, 600f, 800f), layout.pageRect(0))
        assertEquals(ViewportRect(100f, 810f, 500f, 1110f), layout.pageRect(1))
        assertEquals(ViewportRect(0f, 1120f, 600f, 1920f), layout.pageRect(2))
    }

    @Test
    fun pageAt_assignsGapToPageAbove() {
        assertEquals(0, layout.pageAt(0f))
        assertEquals(0, layout.pageAt(805f))
        assertEquals(1, layout.pageAt(810f))
        assertEquals(2, layout.pageAt(5000f))
        assertEquals(0, layout.pageAt(-5f))
    }

    @Test
    fun pagesIn_returnsOnlyIntersectingPages() {
        assertEquals(0..0, layout.pagesIn(ViewportRect(0f, 0f, 600f, 800f)))
        assertEquals(0..1, layout.pagesIn(ViewportRect(0f, 700f, 600f, 900f)))
        assertEquals(0..2, layout.pagesIn(ViewportRect(0f, 0f, 600f, 1920f)))
        // Entirely inside the gap between pages 0 and 1.
        assertTrue(layout.pagesIn(ViewportRect(0f, 801f, 600f, 809f)).isEmpty())
        // Top edge in a gap: starts at the next page.
        assertEquals(1..1, layout.pagesIn(ViewportRect(0f, 805f, 600f, 900f)))
        assertTrue(layout.pagesIn(ViewportRect(0f, 1920f, 600f, 2000f)).isEmpty())
    }

    @Test
    fun visibleRectOnPage_isInPageLocalPoints() {
        val visible = ViewportRect(0f, 700f, 600f, 900f)
        assertEquals(ViewportRect(0f, 700f, 600f, 800f), layout.visibleRectOnPage(0, visible))
        assertEquals(ViewportRect(0f, 0f, 400f, 90f), layout.visibleRectOnPage(1, visible))
        // Viewport only over the side margin of the narrow page.
        assertNull(layout.visibleRectOnPage(1, ViewportRect(0f, 850f, 90f, 900f)))
    }

    @Test
    fun transformState_scrollsWholeDocumentAtFitWidth() {
        val state = DocumentTransformState(layout)
        state.updateViewport(1200f, 1000f) // fit width = 2 px/pt

        assertEquals(2f, state.scale, 0.001f)
        assertEquals(0f, state.offsetY, 0.001f) // starts at the top of page 0

        state.onPan(0f, -1_000_000f)
        assertEquals(1000f - 1920f * 2f, state.offsetY, 0.001f)
        val visible = state.getVisibleRectPoints()
        assertEquals(1920f, visible.bottom, 0.01f)
        assertEquals(2..2, layout.pagesIn(visible))
    }

    @Test
    fun transformState_fitPageUsesTallestPage() {
        val state = DocumentTransformState(layout)
        state.updateViewport(1200f, 400f)
        assertEquals(0.5f, state.minScale, 0.001f) // 400 px / 800 pt
    }
}
