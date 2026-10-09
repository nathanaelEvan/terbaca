package app.terbaca.feature.reader

import androidx.compose.ui.geometry.Offset
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.pyramid.ViewportRect
import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadLayoutTest {

    private val pages = List(5) { PageInfo(index = it, widthPoints = 600f, heightPoints = 800f) }

    @Test
    fun spread_withCoverAlone_putsCoverOnItsOwnRowThenPairs() {
        val layout = DocumentLayout(pages, spread = true, coverAlone = true)
        // Rows: [0], [1, 2], [3, 4]; width of a pair = 600 + 8 + 600.
        assertEquals(1208f, layout.width, 0.01f)
        assertEquals(3 * 800f + 2 * 8f, layout.height, 0.01f)
        assertEquals(ViewportRect(304f, 0f, 904f, 800f), layout.pageRect(0))
        assertEquals(ViewportRect(0f, 808f, 600f, 1608f), layout.pageRect(1))
        assertEquals(ViewportRect(608f, 808f, 1208f, 1608f), layout.pageRect(2))
        assertEquals(1..2, layout.rowPagesAt(1000f))
        assertEquals(1, layout.pageAt(1000f))
    }

    @Test
    fun spread_withoutCoverAlone_pairsFromFirstPage_andLastPageAlone() {
        val layout = DocumentLayout(pages, spread = true, coverAlone = false)
        assertEquals(0..1, layout.rowPagesAt(10f))
        assertEquals(4..4, layout.rowPagesAt(layout.height - 1f))
        assertEquals(ViewportRect(304f, 1616f, 904f, 2416f), layout.pageRect(4))
    }

    @Test
    fun spread_pagesInCoversWholeRows() {
        val layout = DocumentLayout(pages, spread = true, coverAlone = true)
        assertEquals(1..4, layout.pagesIn(ViewportRect(0f, 900f, 1208f, 1700f)))
    }

    @Test
    fun singlePages_unchangedByDefault() {
        val layout = DocumentLayout(pages)
        assertEquals(600f, layout.width, 0.01f)
        assertEquals(2..2, layout.rowPagesAt(1700f))
    }

    @Test
    fun pagePointAt_findsRightPageOfSpread() {
        val state = DocumentTransformState(DocumentLayout(pages, spread = true, coverAlone = true))
        state.updateViewport(1208f, 900f) // fit-width scale 1
        state.onPan(0f, -808f)
        val point = state.pagePointAt(Offset(700f, 100f))!!
        assertEquals(2, point.pageIndex)
        assertEquals(92f, point.x, 0.01f)
        assertEquals(100f, point.y, 0.01f)
    }
}
