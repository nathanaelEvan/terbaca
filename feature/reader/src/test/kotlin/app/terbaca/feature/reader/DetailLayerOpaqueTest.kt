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

    // isViewSharp: where the PRD §7 "sharp" trace sections end (crossfade included).

    @Test
    fun viewSharp_onlyOnceEveryPageHasFinishedItsFade() {
        val loadedNow = tiles.associate { it.key to now }
        assertFalse("loaded but fade just started", isViewSharp(listOf(tiles), { true }, loadedNow, now))
        assertFalse(
            "one frame before the fade ends",
            isViewSharp(listOf(tiles), { true }, loadedNow, now + TILE_CROSSFADE_DURATION_MS - 1)
        )
        assertTrue(isViewSharp(listOf(tiles), { true }, loadedNow, now + TILE_CROSSFADE_DURATION_MS))
    }

    @Test
    fun viewSharp_unrevealedTileIsNotSharp() {
        val fades = mapOf(tiles[0].key to faded, tiles[1].key to TILE_UNREVEALED)
        assertFalse(isViewSharp(listOf(tiles), { true }, fades, now))
    }

    @Test
    fun viewSharp_oneFadingPageKeepsTheWholeViewNotSharp() {
        val otherPage = listOf(
            TileBounds(TileKey(pageIndex = 1, level = 1, col = 0, row = 0), ViewportRect(0f, 0f, 100f, 100f), 512, 512)
        )
        val fades = tiles.associate { it.key to faded } + (otherPage[0].key to now - 1)
        assertFalse(isViewSharp(listOf(tiles, otherPage), { true }, fades, now))
        assertTrue(isViewSharp(listOf(tiles), { true }, fades, now))
    }

    @Test
    fun viewSharp_noPagesIsNotSharp() {
        assertFalse(isViewSharp(emptyList(), { true }, emptyMap(), now))
    }
}
