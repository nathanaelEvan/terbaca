package app.terbaca.core.render.cache

import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sun.misc.Unsafe
import java.io.File
import java.nio.file.Files

class ThumbnailDiskCacheTest {

    private lateinit var testDir: File

    private val unsafe: Unsafe by lazy {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        field.get(null) as Unsafe
    }

    private fun createFakeBitmap(): Bitmap {
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    @Before
    fun setup() {
        testDir = Files.createTempDirectory("thumb_cache_test").toFile()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun testPutAndGet() {
        val fakeBitmap = createFakeBitmap()
        val cache = ThumbnailDiskCache(
            directory = testDir,
            maxBytes = 10_000L,
            writer = { _, file -> file.writeBytes(ByteArray(100)); true },
            reader = { file -> if (file.exists()) fakeBitmap else null }
        )

        assertNull(cache.get("hashA", 0, 400))
        assertFalse(cache.contains("hashA", 0, 400))

        assertTrue(cache.put("hashA", 0, 400, fakeBitmap))
        assertTrue(cache.contains("hashA", 0, 400))

        val retrieved = cache.get("hashA", 0, 400)
        assertNotNull(retrieved)
        assertEquals(fakeBitmap, retrieved)
        assertEquals(100L, cache.sizeBytes())
    }

    @Test
    fun testRemove() {
        val fakeBitmap = createFakeBitmap()
        val cache = ThumbnailDiskCache(
            directory = testDir,
            maxBytes = 10_000L,
            writer = { _, file -> file.writeBytes(ByteArray(100)); true },
            reader = { file -> if (file.exists()) fakeBitmap else null }
        )

        cache.put("hashA", 0, 400, fakeBitmap)
        assertEquals(100L, cache.sizeBytes())

        assertTrue(cache.remove("hashA", 0, 400))
        assertFalse(cache.contains("hashA", 0, 400))
        assertNull(cache.get("hashA", 0, 400))
        assertEquals(0L, cache.sizeBytes())

        assertFalse(cache.remove("hashA", 0, 400))
    }

    @Test
    fun testClear() {
        val fakeBitmap = createFakeBitmap()
        val cache = ThumbnailDiskCache(
            directory = testDir,
            maxBytes = 10_000L,
            writer = { _, file -> file.writeBytes(ByteArray(100)); true },
            reader = { file -> if (file.exists()) fakeBitmap else null }
        )

        cache.put("hashA", 0, 400, fakeBitmap)
        cache.put("hashA", 1, 400, fakeBitmap)
        assertEquals(200L, cache.sizeBytes())

        cache.clear()
        assertEquals(0L, cache.sizeBytes())
        assertFalse(cache.contains("hashA", 0, 400))
        assertFalse(cache.contains("hashA", 1, 400))
    }

    @Test
    fun testLruEvictionWhenBudgetExceeded() {
        val fakeBitmap = createFakeBitmap()
        // Budget fits 2 entries of 100 bytes each
        val cache = ThumbnailDiskCache(
            directory = testDir,
            maxBytes = 250L,
            writer = { _, file -> file.writeBytes(ByteArray(100)); true },
            reader = { file -> if (file.exists()) fakeBitmap else null }
        )

        cache.put("hashA", 0, 400, fakeBitmap)
        File(testDir, "${cache.key("hashA", 0, 400)}.webp").setLastModified(1000L)

        cache.put("hashA", 1, 400, fakeBitmap)
        File(testDir, "${cache.key("hashA", 1, 400)}.webp").setLastModified(2000L)

        assertEquals(200L, cache.sizeBytes())

        // Adding third entry exceeds 250 byte budget -> should evict page 0 (oldest lastModified)
        cache.put("hashA", 2, 400, fakeBitmap)
        File(testDir, "${cache.key("hashA", 2, 400)}.webp").setLastModified(3000L)

        assertEquals(200L, cache.sizeBytes())
        assertFalse("Page 0 should be evicted", cache.contains("hashA", 0, 400))
        assertTrue("Page 1 should remain", cache.contains("hashA", 1, 400))
        assertTrue("Page 2 should remain", cache.contains("hashA", 2, 400))
    }

    @Test
    fun testFailedWriteCleansUpTempFile() {
        val fakeBitmap = createFakeBitmap()
        val cache = ThumbnailDiskCache(
            directory = testDir,
            maxBytes = 10_000L,
            writer = { _, file ->
                file.writeBytes(ByteArray(50))
                false // Simulate write failure
            },
            reader = { null }
        )

        assertFalse(cache.put("hashFail", 0, 400, fakeBitmap))
        assertFalse(cache.contains("hashFail", 0, 400))
        assertEquals(0L, cache.sizeBytes())
        assertEquals(0, testDir.listFiles()?.size ?: 0)
    }
}
