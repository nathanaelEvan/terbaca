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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test
    fun testTrimDuringConcurrentWriteKeepsTempFileAndExactSize() {
        val fakeBitmap = createFakeBitmap()
        val slowWriteStarted = CountDownLatch(1)
        val releaseSlowWrite = CountDownLatch(1)
        // Budget fits one 100-byte entry, so every put trims.
        val cache = ThumbnailDiskCache(
            directory = testDir,
            maxBytes = 150L,
            writer = { _, file ->
                file.writeBytes(ByteArray(100))
                if (file.name.startsWith("slow_")) {
                    slowWriteStarted.countDown()
                    releaseSlowWrite.await(5, TimeUnit.SECONDS)
                }
                true
            },
            reader = { fakeBitmap }
        )

        val executor = Executors.newSingleThreadExecutor()
        try {
            val slowPut = executor.submit<Boolean> { cache.put("slow", 0, 400, fakeBitmap) }
            assertTrue(slowWriteStarted.await(5, TimeUnit.SECONDS))

            // These trim while the slow writer's temp file sits, unfinished, in the same directory.
            assertTrue(cache.put("fast", 0, 400, fakeBitmap))
            assertTrue(cache.put("fast", 1, 400, fakeBitmap))
            assertEquals(1, testDir.listFiles { f -> f.name.endsWith(".tmp") }!!.size)

            releaseSlowWrite.countDown()
            assertTrue("slow writer's temp file must survive the trim", slowPut.get(5, TimeUnit.SECONDS))
        } finally {
            releaseSlowWrite.countDown()
            executor.shutdownNow()
        }

        val webpBytes = testDir.listFiles { f -> f.name.endsWith(".webp") }!!.sumOf { it.length() }
        assertEquals(webpBytes, cache.sizeBytes())
        assertTrue(cache.sizeBytes() <= 150L)
    }

    @Test
    fun testConcurrentWritersOfSameKeyUseDistinctTempFiles() {
        val fakeBitmap = createFakeBitmap()
        val tempNames = java.util.Collections.synchronizedSet(HashSet<String>())
        val bothWriting = CountDownLatch(2)
        val cache = ThumbnailDiskCache(
            directory = testDir,
            maxBytes = 10_000L,
            writer = { _, file ->
                tempNames += file.name
                file.writeBytes(ByteArray(100))
                bothWriting.countDown()
                bothWriting.await(5, TimeUnit.SECONDS)
                true
            },
            reader = { fakeBitmap }
        )

        val executor = Executors.newFixedThreadPool(2)
        try {
            val puts = List(2) { executor.submit<Boolean> { cache.put("same", 0, 400, fakeBitmap) } }
            puts.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(2, tempNames.size)
        assertEquals(100L, cache.sizeBytes())
        assertEquals(listOf("${cache.key("same", 0, 400)}.webp"), testDir.list()!!.toList())
    }

    @Test
    fun testStaleTempFilesAreDeletedAndNotCounted() {
        File(testDir, "hashA_0_400.123.tmp").writeBytes(ByteArray(500))
        File(testDir, "hashA_1_400.webp").writeBytes(ByteArray(100))

        val cache = ThumbnailDiskCache(
            directory = testDir,
            maxBytes = 10_000L,
            writer = { _, file -> file.writeBytes(ByteArray(100)); true },
            reader = { null }
        )

        assertEquals(100L, cache.sizeBytes())
        assertEquals(listOf("hashA_1_400.webp"), testDir.list()!!.toList())
    }

    @Test
    fun testWritesCountsSuccessfulPutsOnly() {
        val fakeBitmap = createFakeBitmap()
        var succeed = true
        val cache = ThumbnailDiskCache(
            directory = testDir,
            maxBytes = 10_000L,
            writer = { _, file -> file.writeBytes(ByteArray(100)); succeed },
            reader = { null }
        )

        assertEquals(0, cache.writes.value)
        cache.put("hashA", 0, 400, fakeBitmap)
        assertEquals(1, cache.writes.value)
        succeed = false
        cache.put("hashA", 1, 400, fakeBitmap)
        assertEquals(1, cache.writes.value)
    }
}
