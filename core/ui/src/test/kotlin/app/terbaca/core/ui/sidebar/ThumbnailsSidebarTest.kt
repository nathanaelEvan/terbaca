package app.terbaca.core.ui.sidebar

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailsSidebarTest {

    @Test
    fun centeredScrollOffset_placesItemInViewportCentre() {
        // 1000 px viewport, 200 px item, 20 px top padding: item top should land at 400 px,
        // i.e. 380 px below the padding, so the offset is -380.
        val offset = centeredScrollOffset(viewportSize = 1000, itemSize = 200, beforeContentPadding = 20)
        assertEquals(-380, offset)
        assertEquals(400, 20 - offset)
    }

    @Test
    fun centeredScrollOffset_itemTallerThanViewportAlignsToTop() {
        assertEquals(20, centeredScrollOffset(viewportSize = 300, itemSize = 500, beforeContentPadding = 20))
    }
}
