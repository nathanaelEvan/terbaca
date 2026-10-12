package app.terbaca.feature.reader

import app.terbaca.core.model.TileKey
import app.terbaca.core.render.PerfTrace
import app.terbaca.core.render.pyramid.TileBounds
import app.terbaca.core.render.pyramid.ViewportRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileRevealTest {

    private fun tile(col: Int) = TileBounds(
        key = TileKey(pageIndex = 0, level = 1, col = col, row = 0),
        pageRect = ViewportRect(col * 100f, 0f, (col + 1) * 100f, 100f),
        pixelWidth = 512,
        pixelHeight = 512
    )

    private val visible = listOf(tile(0), tile(1), tile(2))
    private val offscreen = tile(3)
    private val now = 10_000L

    @Test
    fun partialBatch_staysHidden() {
        val fades = mutableMapOf(visible[0].key to TILE_UNREVEALED)
        val loaded = setOf(visible[0].key)
        val since = revealTiles(visible, { it in loaded }, fades, pendingSince = null, now)
        assertEquals(now, since)
        assertEquals(TILE_UNREVEALED, fades[visible[0].key])
    }

    @Test
    fun completeBatch_revealsAllTogether() {
        val fades = visible.associate { it.key to TILE_UNREVEALED }.toMutableMap()
        val since = revealTiles(visible, { true }, fades, pendingSince = now - 50, now)
        assertNull(since)
        visible.forEach { assertEquals(now, fades[it.key]) }
    }

    @Test
    fun alreadyRevealedTilesKeepTheirFade() {
        val fades = mutableMapOf(visible[0].key to 1_000L, visible[1].key to TILE_UNREVEALED, visible[2].key to TILE_UNREVEALED)
        revealTiles(visible, { true }, fades, pendingSince = null, now)
        assertEquals(1_000L, fades[visible[0].key])
        assertEquals(now, fades[visible[1].key])
    }

    @Test
    fun timeout_revealsWhatArrived() {
        val fades = mutableMapOf(visible[0].key to TILE_UNREVEALED)
        val loaded = setOf(visible[0].key)
        val since = revealTiles(visible, { it in loaded }, fades, pendingSince = now - TILE_REVEAL_TIMEOUT_MS, now)
        assertNull(since)
        assertEquals(now, fades[visible[0].key])
    }

    @Test
    fun overBase_keepsWaitingPastShortTimeout() {
        val fades = mutableMapOf(visible[0].key to TILE_UNREVEALED)
        val loaded = setOf(visible[0].key)
        val since = now - TILE_REVEAL_TIMEOUT_MS
        val result = revealTiles(
            visible, { it in loaded }, fades, pendingSince = since, now,
            timeoutMs = TILE_REVEAL_TIMEOUT_OVER_BASE_MS
        )
        assertEquals(since, result)
        assertEquals(TILE_UNREVEALED, fades[visible[0].key])
    }

    @Test
    fun offscreenArrival_isMarkedFullyFaded() {
        val fades = mutableMapOf(offscreen.key to TILE_UNREVEALED)
        val since = revealTiles(visible, { it == offscreen.key }, fades, pendingSince = null, now)
        assertNull(since)
        assertEquals(now - TILE_CROSSFADE_DURATION_MS, fades[offscreen.key])
        assertEquals(true, isDetailLayerOpaque(listOf(offscreen), { true }, fades, now))
    }

    @Test
    fun unrevealedTile_isNotOpaque() {
        val fades = visible.associate { it.key to TILE_UNREVEALED }
        assertEquals(false, isDetailLayerOpaque(visible, { true }, fades, now))
    }

    @Test
    fun completeBatch_emitsRevealStartMark() {
        val marks = mutableListOf<String>()
        val fakeTracer = object : PerfTrace.Tracer {
            override val isEnabled: Boolean = true
            override fun beginAsync(name: String, cookie: Int) {}
            override fun endAsync(name: String, cookie: Int) {}
            override fun mark(name: String) { marks += name }
        }
        val prevTracer = PerfTrace.tracer
        PerfTrace.tracer = fakeTracer
        try {
            val fades = visible.associate { it.key to TILE_UNREVEALED }.toMutableMap()
            revealTiles(visible, { true }, fades, pendingSince = now - 50, now)
            assertTrue("Expected TerbacaRevealStart mark", marks.contains(PerfTrace.REVEAL_START))
        } finally {
            PerfTrace.tracer = prevTracer
        }
    }
}
