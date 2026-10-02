package app.terbaca.feature.reader

import app.terbaca.core.model.TileKey
import app.terbaca.core.render.pyramid.TileBounds
import app.terbaca.core.render.pyramid.ViewportRect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailLayerOpaqueTest {

    private fun tile(col: Int) = TileBounds(
        key = TileKey(pageIndex = 0, level = 1, col = col, row = 0),
        pageRect = ViewportRect(col * 100f, 0f, (col + 1) * 100f, 100f),
        pixelWidth = 512,
        pixelHeight = 512
    )

    private val tiles = listOf(tile(0), tile(1))
    private val now = 10_000L
    private val faded = now - TILE_CROSSFADE_DURATION_MS

    @Test
    fun allLoadedAndFaded_isOpaque() {
        val fades = tiles.associate { it.key to faded }
        assertTrue(isDetailLayerOpaque(tiles, { true }, fades, now))
    }

    @Test
    fun oneTileMissing_isNotOpaque() {
        val fades = tiles.associate { it.key to faded }
        assertFalse(isDetailLayerOpaque(tiles, { it != tiles[1].key }, fades, now))
    }

    @Test
    fun oneTileStillFading_isNotOpaque() {
        val fades = mapOf(tiles[0].key to faded, tiles[1].key to now - 1)
        assertFalse(isDetailLayerOpaque(tiles, { true }, fades, now))
    }

    @Test
    fun missingFadeStart_isNotOpaque() {
        // drawTileBoundsList treats a missing fade start as alpha 0, so the base must still draw.
        assertFalse(isDetailLayerOpaque(tiles, { true }, emptyMap(), now))
    }

    @Test
    fun noVisibleTiles_isNotOpaque() {
        assertFalse(isDetailLayerOpaque(emptyList(), { true }, emptyMap(), now))
    }
}
