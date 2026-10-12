package app.terbaca.feature.reader

import android.graphics.Bitmap
import app.terbaca.core.model.TileKey
import app.terbaca.core.render.PerfTrace
import app.terbaca.core.render.cache.TileCache
import app.terbaca.core.render.cache.TileMemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class TileSwapTest {

    private val unsafe: Unsafe by lazy {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        field.get(null) as Unsafe
    }

    private fun createFakeBitmap(): Bitmap {
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    @Test
    fun swapPendingTiles_emitsSwappedMark() {
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
            val pending = ArrayDeque<Pair<TileKey, Bitmap>>()
            val key1 = TileKey(0, 0, 0, 0)
            val key2 = TileKey(0, 0, 1, 0)
            pending.add(key1 to createFakeBitmap())
            pending.add(key2 to createFakeBitmap())

            val cache = TileCache(budget = TileMemoryBudget(10_000, 10_000), bitmapSizer = { 100L })
            val fades = HashMap<TileKey, Long>()
            val swappedKeys = mutableListOf<TileKey>()

            swapPendingTiles(pending, cache, fades) { swappedKeys += it }

            assertEquals(listOf(key1, key2), swappedKeys)
            assertTrue("Expected TerbacaTileSwapped:2 mark", marks.contains("${PerfTrace.TILE_SWAPPED}:2"))
        } finally {
            PerfTrace.tracer = prevTracer
        }
    }

    @Test
    fun swapPendingTiles_empty_doesNotEmitMark() {
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
            val pending = ArrayDeque<Pair<TileKey, Bitmap>>()
            val cache = TileCache(budget = TileMemoryBudget(10_000, 10_000))
            val fades = HashMap<TileKey, Long>()

            swapPendingTiles(pending, cache, fades) {}

            assertTrue(marks.isEmpty())
        } finally {
            PerfTrace.tracer = prevTracer
        }
    }

    @Test
    fun swapBudget_whenGesturing_returnsCapOfTwo() {
        assertEquals(2, swapBudget(isGesturing = true, isFlinging = false))
    }

    @Test
    fun swapBudget_whenFlinging_returnsCapOfTwo() {
        assertEquals(2, swapBudget(isGesturing = false, isFlinging = true))
    }

    @Test
    fun swapBudget_whenGesturingAndFlinging_returnsCapOfTwo() {
        assertEquals(2, swapBudget(isGesturing = true, isFlinging = true))
    }

    @Test
    fun swapBudget_whenIdle_returnsUncappedBudget() {
        assertEquals(IDLE_SWAP_BUDGET, swapBudget(isGesturing = false, isFlinging = false))
        assertTrue(swapBudget(isGesturing = false, isFlinging = false) > 2)
    }

    @Test
    fun swapPendingTiles_withIdleBudget_swapsAllArrivedTilesInOneFrame() {
        val pending = ArrayDeque<Pair<TileKey, Bitmap>>()
        val count = 10
        val keys = (0 until count).map { TileKey(0, 0, it, 0) }
        for (k in keys) {
            pending.add(k to createFakeBitmap())
        }
        val cache = TileCache(budget = TileMemoryBudget(10_000, 10_000), bitmapSizer = { 100L })
        val fades = HashMap<TileKey, Long>()
        val swapped = mutableListOf<TileKey>()

        swapPendingTiles(pending, cache, fades, maxSwaps = IDLE_SWAP_BUDGET) { swapped += it }

        assertEquals(keys, swapped)
        assertTrue(pending.isEmpty())
    }
}
