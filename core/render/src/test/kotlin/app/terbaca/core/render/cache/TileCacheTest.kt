package app.terbaca.core.render.cache

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import app.terbaca.core.model.TileKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class TileCacheTest {

    private val unsafe: Unsafe by lazy {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        field.get(null) as Unsafe
    }

    private fun createFakeBitmap(): Bitmap {
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    private fun createTestCache(
        budget: TileMemoryBudget,
        recycled: MutableList<Bitmap>
    ): TileCache {
        return TileCache(
            budget = budget,
            bitmapSizer = { 100L },
            bitmapReleaser = { recycled.add(it) },
            isBitmapRecycled = { it in recycled }
        )
    }

    @Test
    fun testMemoryBudgetScaling() {
        val standard = TileMemoryBudget.fromMemoryClass(256)
        assertEquals(80L * 1024 * 1024, standard.hotBytes)
        assertEquals(80L * 1024 * 1024, standard.warmBytes)
        assertEquals(20L * 1024 * 1024, standard.thumbnailBytes)

        val half = TileMemoryBudget.fromMemoryClass(128)
        assertEquals(40L * 1024 * 1024, half.hotBytes)
        assertEquals(40L * 1024 * 1024, half.warmBytes)
        assertEquals(10L * 1024 * 1024, half.thumbnailBytes)

        val clamped = TileMemoryBudget.fromMemoryClass(32)
        assertEquals(20L * 1024 * 1024, clamped.hotBytes)
        assertEquals(20L * 1024 * 1024, clamped.warmBytes)
        assertEquals(5L * 1024 * 1024, clamped.thumbnailBytes)
    }

    @Test
    fun testPutAndGet() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 500),
            recycled = recycled
        )

        val key1 = TileKey(0, 1, 0, 0)
        val bitmap1 = createFakeBitmap()
        cache.pinHot(setOf(key1))
        cache.put(key1, bitmap1)

        assertEquals(bitmap1, cache.get(key1))
        assertEquals(1, cache.hotCount)
        assertEquals(0, cache.warmCount)
        assertEquals(100L, cache.hotBytes)
        assertEquals(0L, cache.warmBytes)

        val key2 = TileKey(0, 1, 0, 1)
        val bitmap2 = createFakeBitmap()
        cache.put(key2, bitmap2) // not pinned -> placed in warm

        assertEquals(bitmap2, cache.get(key2))
        assertEquals(1, cache.hotCount)
        assertEquals(1, cache.warmCount)
        assertEquals(100L, cache.hotBytes)
        assertEquals(100L, cache.warmBytes)
    }

    @Test
    fun testLruEvictionByByteBudget() {
        val recycled = mutableListOf<Bitmap>()
        // Warm budget: 250 bytes. Each bitmap: 100 bytes -> max 2 bitmaps in warm.
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 250, thumbnailBytes = 500),
            recycled = recycled
        )

        val b1 = createFakeBitmap()
        val b2 = createFakeBitmap()
        val b3 = createFakeBitmap()

        val k1 = TileKey(0, 1, 0, 0)
        val k2 = TileKey(0, 1, 0, 1)
        val k3 = TileKey(0, 1, 0, 2)

        cache.put(k1, b1)
        cache.put(k2, b2)
        assertEquals(2, cache.warmCount)
        assertEquals(200L, cache.warmBytes)
        assertTrue(recycled.isEmpty())

        // Inserting 3rd bitmap exceeds 250 warm bytes -> k1 (eldest) must be evicted & recycled
        cache.put(k3, b3)
        assertEquals(2, cache.warmCount)
        assertEquals(200L, cache.warmBytes)
        assertNull("k1 must be evicted", cache.get(k1))
        assertNotNull("k2 must still be present", cache.get(k2))
        assertNotNull("k3 must still be present", cache.get(k3))
        assertEquals(listOf(b1), recycled)
    }

    @Test
    fun testPinHotPreventsEvictionAndPromotesDemotes() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 200, warmBytes = 200, thumbnailBytes = 500),
            recycled = recycled
        )

        val k1 = TileKey(0, 1, 0, 0)
        val k2 = TileKey(0, 1, 0, 1)
        val b1 = createFakeBitmap()
        val b2 = createFakeBitmap()

        // Pin k1 into hot
        cache.pinHot(setOf(k1))
        cache.put(k1, b1)
        cache.put(k2, b2) // warm

        assertEquals(1, cache.hotCount)
        assertEquals(1, cache.warmCount)

        // Now pin k2, unpin k1
        cache.pinHot(setOf(k2))
        assertEquals(1, cache.hotCount)
        assertEquals(1, cache.warmCount)
        assertEquals(b2, cache.get(k2))
        assertEquals(b1, cache.get(k1))

        // Put k3 into warm: warm now has k1 and k3 (200 bytes, at capacity)
        val k3 = TileKey(0, 1, 0, 2)
        val b3 = createFakeBitmap()
        cache.put(k3, b3)
        assertEquals(2, cache.warmCount)
        assertEquals(200L, cache.warmBytes)

        // Put k4 into warm: warm capacity exceeded (300 > 200) -> k1 evicted & recycled
        val k4 = TileKey(0, 1, 0, 3)
        val b4 = createFakeBitmap()
        cache.put(k4, b4)
        assertEquals(2, cache.warmCount)
        assertNull(cache.get(k1))
        assertEquals(listOf(b1), recycled)
        // Hot tile k2 is still intact!
        assertEquals(b2, cache.get(k2))
    }

    @Test
    fun testDeterministicRecyclingOnRemoveAndClear() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 500),
            recycled = recycled
        )

        val k1 = TileKey(0, 1, 0, 0)
        val b1 = createFakeBitmap()
        cache.pinHot(setOf(k1))
        cache.put(k1, b1)

        val k2 = TileKey(0, 1, 0, 1)
        val b2 = createFakeBitmap()
        cache.put(k2, b2)

        assertTrue(cache.remove(k1))
        assertEquals(listOf(b1), recycled)
        assertEquals(0, cache.hotCount)
        assertEquals(0L, cache.hotBytes)

        cache.clear()
        assertEquals(listOf(b1, b2), recycled)
        assertEquals(0, cache.warmCount)
        assertEquals(0L, cache.warmBytes)
    }

    @Test
    fun testOnTrimMemory_runningLow_evictsWarmOnly() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 500),
            recycled = recycled
        )

        val kHot = TileKey(0, 1, 0, 0)
        val bHot = createFakeBitmap()
        cache.pinHot(setOf(kHot))
        cache.put(kHot, bHot)

        val kWarm = TileKey(0, 1, 0, 1)
        val bWarm = createFakeBitmap()
        cache.put(kWarm, bWarm)

        cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertEquals(0, cache.warmCount)
        assertEquals(0L, cache.warmBytes)
        assertEquals(listOf(bWarm), recycled)

        // Hot tile preserved
        assertEquals(1, cache.hotCount)
        assertEquals(100L, cache.hotBytes)
        assertEquals(bHot, cache.get(kHot))
    }

    @Test
    fun testOnTrimMemory_uiHidden_evictsWarmOnly() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 500),
            recycled = recycled
        )

        val kHot = TileKey(0, 1, 0, 0)
        val bHot = createFakeBitmap()
        cache.pinHot(setOf(kHot))
        cache.put(kHot, bHot)

        val kWarm = TileKey(0, 1, 0, 1)
        val bWarm = createFakeBitmap()
        cache.put(kWarm, bWarm)

        cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        assertEquals(0, cache.warmCount)
        assertEquals(listOf(bWarm), recycled)
        assertEquals(1, cache.hotCount)
        assertEquals(bHot, cache.get(kHot))
    }

    @Test
    fun testOnTrimMemory_backgrounded_keepsOnlyVisibleContent() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 500),
            recycled = recycled
        )

        val kHot = TileKey(0, 1, 0, 0)
        val bHot = createFakeBitmap()
        cache.pinHot(setOf(kHot))
        cache.put(kHot, bHot)

        val kWarm = TileKey(0, 1, 0, 1)
        val bWarm = createFakeBitmap()
        cache.put(kWarm, bWarm)

        val bThumb = createFakeBitmap()
        cache.putThumbnail(0, bThumb)

        cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

        assertEquals(0, cache.warmCount)
        assertEquals(0, cache.thumbnailCount)
        assertEquals(0L, cache.thumbnailBytes)
        assertTrue(recycled.containsAll(listOf(bWarm, bThumb)))
        assertEquals("Visible tile must survive", bHot, cache.get(kHot))
    }

    @Test
    fun testOnTrimMemory_runningLow_keepsThumbnails() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 500),
            recycled = recycled
        )
        val bThumb = createFakeBitmap()
        cache.putThumbnail(0, bThumb)

        cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertEquals(bThumb, cache.getThumbnail(0))
        assertTrue(recycled.isEmpty())
    }

    @Test
    fun testByteCountsStayExactWhenBitmapRecycledElsewhere() {
        val recycled = mutableListOf<Bitmap>()
        val externallyRecycled = mutableSetOf<Bitmap>()
        // Like the real sizer: a recycled bitmap reports 0 bytes.
        val cache = TileCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 500),
            bitmapSizer = { if (it in externallyRecycled) 0L else 100L },
            bitmapReleaser = { recycled.add(it) },
            isBitmapRecycled = { it in externallyRecycled || it in recycled }
        )
        val k1 = TileKey(0, 1, 0, 0)
        val b1 = createFakeBitmap()
        cache.put(k1, b1)
        assertEquals(100L, cache.warmBytes)

        externallyRecycled += b1
        assertNull(cache.get(k1))
        assertEquals("Removed entry must subtract its insert-time size", 0L, cache.warmBytes)
        assertEquals(0, cache.warmCount)
    }

    @Test
    fun testPutReplacesAndReleasesPreviousBitmap() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 500),
            recycled = recycled
        )
        val k1 = TileKey(0, 1, 0, 0)
        val old = createFakeBitmap()
        cache.put(k1, old) // warm

        // The key becomes visible and a sharper render for it arrives.
        val fresh = createFakeBitmap()
        cache.pinHot(setOf(k1)) // promotes `old` to hot
        cache.put(k1, fresh)

        assertEquals(fresh, cache.get(k1))
        assertEquals(1, cache.hotCount)
        assertEquals(0, cache.warmCount)
        assertEquals(100L, cache.hotBytes)
        assertEquals(listOf(old), recycled)
    }

    @Test
    fun testThumbnailsDoNotCollideWithTiles() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 500),
            recycled = recycled
        )
        val thumb = createFakeBitmap()
        cache.putThumbnail(0, thumb)

        assertNull(cache.get(TileKey(0, 0, 0, 0)))
        assertNull(cache.get(TileCache.baseLayerKey(0)))
        assertEquals(thumb, cache.getThumbnail(0))
    }

    @Test
    fun testThumbnailTier_lruEviction() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 250),
            recycled = recycled
        )

        val b1 = createFakeBitmap()
        val b2 = createFakeBitmap()
        val b3 = createFakeBitmap()

        cache.putThumbnail(0, b1)
        cache.putThumbnail(1, b2)
        assertEquals(2, cache.thumbnailCount)
        assertEquals(200L, cache.thumbnailBytes)

        cache.putThumbnail(2, b3)
        assertEquals(2, cache.thumbnailCount)
        assertEquals(200L, cache.thumbnailBytes)
        assertNull("page 0 thumbnail evicted", cache.getThumbnail(0))
        assertEquals(listOf(b1), recycled)
    }

    @Test
    fun testClearThumbnails() {
        val recycled = mutableListOf<Bitmap>()
        val cache = createTestCache(
            budget = TileMemoryBudget(hotBytes = 1000, warmBytes = 1000, thumbnailBytes = 500),
            recycled = recycled
        )

        val tileKey = TileKey(0, 1, 0, 0)
        val tileBmp = createFakeBitmap()
        cache.pinHot(setOf(tileKey))
        cache.put(tileKey, tileBmp)

        val thumbBmp = createFakeBitmap()
        cache.putThumbnail(0, thumbBmp)

        assertEquals(1, cache.hotCount)
        assertEquals(1, cache.thumbnailCount)

        cache.clearThumbnails()

        assertEquals(0, cache.thumbnailCount)
        assertEquals(0L, cache.thumbnailBytes)
        assertNull(cache.getThumbnail(0))
        // Hot tile is preserved
        assertEquals(1, cache.hotCount)
        assertEquals(tileBmp, cache.get(tileKey))
        assertEquals(listOf(thumbBmp), recycled)
    }

    @Test
    fun testBaseLayerKey_matchesTilePyramidBaseBoundsKey() {
        val pageInfo = app.terbaca.core.model.PageInfo(index = 5, widthPoints = 612f, heightPoints = 792f)
        val baseBounds = app.terbaca.core.render.pyramid.TilePyramid.computeBaseLevelBounds(pageInfo, 1.5f)
        val cacheKey = TileCache.baseLayerKey(5)
        assertEquals(cacheKey, baseBounds.key)
        assertEquals(-2, baseBounds.key.level)
    }
}
