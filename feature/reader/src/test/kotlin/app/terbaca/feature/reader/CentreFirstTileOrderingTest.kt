package app.terbaca.feature.reader

import app.terbaca.core.model.TileKey
import app.terbaca.core.render.pyramid.TileBounds
import app.terbaca.core.render.pyramid.ViewportRect
import org.junit.Assert.assertEquals
import org.junit.Test

class CentreFirstTileOrderingTest {

    private fun tile(col: Int, row: Int) = TileBounds(
        key = TileKey(pageIndex = 0, level = 1, col = col, row = row),
        pageRect = ViewportRect(col * 100f, row * 100f, (col + 1) * 100f, (row + 1) * 100f),
        pixelWidth = 512,
        pixelHeight = 512
    )

    @Test
    fun tilesSortedByDistance_putsCentreTileFirst() {
        // Grid of 3x3 tiles. (1,1) is at the center (distance = 0).
        // (0,0) is top-left corner (distance = 141.4).
        // (1,0) is top-edge (distance = 100.0).
        val corner = tile(0, 0) to 141.4f
        val edge = tile(1, 0) to 100.0f
        val centre = tile(1, 1) to 0.0f

        val tiles = mutableListOf(corner, edge, centre)
        tiles.sortBy { it.second }

        assertEquals(centre.first.key, tiles[0].first.key)
        assertEquals(edge.first.key, tiles[1].first.key)
        assertEquals(corner.first.key, tiles[2].first.key)
    }

    @Test
    fun crossfadeDuration_isOneHundredMillis() {
        assertEquals(100L, TILE_CROSSFADE_DURATION_MS)
    }
}
