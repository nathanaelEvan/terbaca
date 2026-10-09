package app.terbaca.feature.reader

import app.terbaca.core.model.PageInfo
import app.terbaca.core.model.PageRect
import org.junit.Assert.assertEquals
import org.junit.Test

class PageThemeTest {

    private val pages = List(12) { PageInfo(index = it, widthPoints = 600f, heightPoints = 800f) }

    /** 1200 px wide: fit-width scale 2. */
    private fun state() = DocumentTransformState(DocumentLayout(pages)).apply { updateViewport(1200f, 1000f) }

    @Test
    fun next_cyclesOriginalDarkSepia() {
        assertEquals(PageTheme.DARK, PageTheme.ORIGINAL.next())
        assertEquals(PageTheme.SEPIA, PageTheme.DARK.next())
        assertEquals(PageTheme.ORIGINAL, PageTheme.SEPIA.next())
    }

    @Test
    fun themeRects_mapsPagesAndImagesToScreen() {
        val state = state()
        state.onPan(0f, -100f)
        val rects = themeRects(state, 0..1, mapOf(1 to listOf(PageRect(100f, 50f, 300f, 250f))))
        assertEquals(2, rects.pageCount)
        // Page 0: (0, 0, 600, 800) pt at scale 2, scrolled up 100 px.
        assertEquals(listOf(0f, -100f, 1200f, 1500f), rects.pages.slice(0..3))
        // Page 1 starts at 808 pt; its image at (100, 50)-(300, 250) pt.
        assertEquals(1, rects.imageCount)
        assertEquals(listOf(200f, (808f + 50f) * 2f - 100f, 600f, (808f + 250f) * 2f - 100f), rects.images.slice(0..3))
    }

    @Test
    fun themeRects_capsPagesAndImages() {
        val state = state()
        val manyImages = List(20) { PageRect(0f, 0f, 10f, 10f) }
        val rects = themeRects(state, 0..11, (0..11).associateWith { manyImages })
        assertEquals(MAX_THEMED_PAGES, rects.pageCount)
        assertEquals(MAX_MASKED_IMAGES, rects.imageCount)
    }
}
