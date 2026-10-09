package app.terbaca.feature.reader

import app.terbaca.core.model.PageInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PeekRegionTest {

    private val letter = PageInfo(index = 4, widthPoints = 612f, heightPoints = 792f)

    @Test
    fun peekRegion_startsJustAboveDestination() {
        // 1224 px wide = scale 2; destination 300 pt, minus 6 pt lead = 588 px.
        val region = peekRegion(letter, pageY = 300f, widthPx = 1224)
        assertEquals(1224, region.fullWidth)
        assertEquals(1584, region.fullHeight)
        assertEquals(588, region.startY)
        assertEquals(367, region.tileHeight) // 0.3 x 612 pt x 2
    }

    @Test
    fun peekRegion_withoutY_showsPageTop() {
        assertEquals(0, peekRegion(letter, pageY = null, widthPx = 1224).startY)
        assertEquals(0, peekRegion(letter, pageY = Float.NaN, widthPx = 1224).startY)
    }

    @Test
    fun peekRegion_nearPageBottom_staysInsidePage() {
        val region = peekRegion(letter, pageY = 790f, widthPx = 1224)
        assertEquals(region.fullHeight - region.tileHeight, region.startY)
    }

    @Test
    fun peekRegion_veryWideCard_isCappedToTileLimit() {
        val region = peekRegion(letter, pageY = 0f, widthPx = 10_000)
        assertEquals(4096, region.fullWidth)
    }
}
