package app.terbaca.core.render.cache

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import app.terbaca.core.model.TileKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/**
 * JVM unit test validating memory cache budget enforcement and leak-free deterministic eviction
 * across 100+ pages per PRD §5 and §8 (M5-02).
 */
class MemoryBudgetProfilingUnitTest {

    private val unsafe: Unsafe by lazy {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        field.get(null) as Unsafe
    }

    private fun createFakeBitmap(): Bitmap {
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    @Test
    fun test100PageSimulation_cacheMemoryStaysStrictlyWithinBudgets() {
        val recycledBitmaps = mutableListOf<Bitmap>()
        val tileSize = 512L * 512L * 4L // 1 MB per tile
        val budget = TileMemoryBudget() // PRD §5 defaults: hot 80 MB, warm 80 MB, thumbnails 20 MB

        val cache = TileCache(
            budget = budget,
            bitmapSizer = { tileSize },
            bitmapReleaser = { recycledBitmaps.add(it) },
            isBitmapRecycled = { it in recycledBitmaps }
        )

        // Simulate scrolling through 105 pages, each page adding 4 tiles and 1 thumbnail
        val totalPages = 105
        var totalTilesCreated = 0

        for (p in 0 until totalPages) {
            // Pin current page as hot
            val pageKeys = (0 until 4).map { TileKey(p, 1, it % 2, it / 2) }.toSet()
            cache.pinHot(pageKeys)

            // Insert 4 detail tiles
            for (key in pageKeys) {
                cache.put(key, createFakeBitmap())
                totalTilesCreated++
            }

            // Insert thumbnail
            cache.putThumbnail(p, createFakeBitmap())

            // Memory must NEVER exceed defined budgets
            assertTrue("Hot memory must stay within budget", cache.hotBytes <= budget.hotBytes)
            assertTrue("Warm memory must stay within budget", cache.warmBytes <= budget.warmBytes)
            assertTrue("Thumbnail memory must stay within budget", cache.thumbnailBytes <= budget.thumbnailBytes)
        }

        // Verify that eviction happened and bitmaps were recycled deterministically
        val totalActiveTiles = cache.hotCount + cache.warmCount
        val totalRecycledTiles = recycledBitmaps.size
        assertTrue("Active tiles must be bounded", totalActiveTiles <= (budget.hotBytes + budget.warmBytes) / tileSize)
        assertTrue("Evicted bitmaps must be deterministically recycled", totalRecycledTiles > 0)
        assertEquals("Total allocated equals active + recycled", totalTilesCreated + totalPages, totalActiveTiles + cache.thumbnailCount + totalRecycledTiles)

        // Test onTrimMemory drops warm and thumbnails
        cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals("Warm cache must be cleared after TRIM_MEMORY_RUNNING_LOW", 0L, cache.warmBytes)

        cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals("Thumbnail cache must be cleared after TRIM_MEMORY_UI_HIDDEN", 0L, cache.thumbnailBytes)
    }
}
