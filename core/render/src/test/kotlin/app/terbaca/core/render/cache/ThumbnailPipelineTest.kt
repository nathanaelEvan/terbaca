package app.terbaca.core.render.cache

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sun.misc.Unsafe
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ThumbnailPipelineTest {

    private lateinit var testDir: File

    private val unsafe: Unsafe by lazy {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        field.get(null) as Unsafe
    }

    private fun createFakeBitmap(): Bitmap {
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    private class FakePageRenderer(
        val renderBlock: suspend (pageIndex: Int) -> Bitmap
    ) : PageRenderer {
        val renderCallCount = AtomicInteger(0)

        override val isServiceConnected: Boolean = true

        override suspend fun openDocument(pfd: ParcelFileDescriptor, password: String?): Long = 1L
        override suspend fun getPageCount(docHandle: Long): Int = 1
        override suspend fun getPageInfo(docHandle: Long, pageIndex: Int): PageInfo =
            PageInfo(pageIndex, 600f, 800f)
        override suspend fun getAllPages(docHandle: Long): List<PageInfo> =
            listOf(PageInfo(0, 600f, 800f))

        override suspend fun renderTile(
            docHandle: Long,
            pageIndex: Int,
            fullWidth: Int,
            fullHeight: Int,
            startX: Int,
            startY: Int,
            tileWidth: Int,
            tileHeight: Int
        ): Bitmap {
            renderCallCount.incrementAndGet()
            return renderBlock(pageIndex)
        }

        override suspend fun closeDocument(docHandle: Long) {}
    }

    @Before
    fun setup() {
        testDir = Files.createTempDirectory("pipeline_test").toFile()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun testMemoryHitSkipsDiskAndRenderer() = runTest {
        val bitmap = createFakeBitmap()
        val memCache = TileCache(
            budget = TileMemoryBudget(),
            bitmapSizer = { 100L },
            bitmapReleaser = {},
            isBitmapRecycled = { false }
        )
        memCache.putThumbnail(0, bitmap)

        val diskCache = ThumbnailDiskCache(
            directory = testDir,
            writer = { _, _ -> true },
            reader = { null }
        )
        val renderer = FakePageRenderer { createFakeBitmap() }
        val pipeline = ThumbnailPipeline(memCache, diskCache, renderer)

        val result = pipeline.getThumbnail(1L, "hash1", PageInfo(0, 600f, 800f))
        assertEquals(bitmap, result)
        assertEquals(0, renderer.renderCallCount.get())
        assertEquals(0L, diskCache.sizeBytes())
    }

    @Test
    fun testDiskHitPopulatesMemoryAndSkipsRenderer() = runTest {
        val diskBitmap = createFakeBitmap()
        val memCache = TileCache(
            budget = TileMemoryBudget(),
            bitmapSizer = { 100L },
            bitmapReleaser = {},
            isBitmapRecycled = { false }
        )
        val diskCache = ThumbnailDiskCache(
            directory = testDir,
            writer = { _, file -> file.writeBytes(ByteArray(50)); true },
            reader = { diskBitmap }
        )
        diskCache.put("hash1", 0, 400, diskBitmap)

        val renderer = FakePageRenderer { createFakeBitmap() }
        val pipeline = ThumbnailPipeline(memCache, diskCache, renderer)

        val result = pipeline.getThumbnail(1L, "hash1", PageInfo(0, 600f, 800f))
        assertEquals(diskBitmap, result)
        assertEquals(0, renderer.renderCallCount.get())
        assertEquals(diskBitmap, memCache.getThumbnail(0))
    }

    @Test
    fun testCacheMissRendersAndCachesToBothTiers() = runTest {
        val renderedBitmap = createFakeBitmap()
        val memCache = TileCache(
            budget = TileMemoryBudget(),
            bitmapSizer = { 100L },
            bitmapReleaser = {},
            isBitmapRecycled = { false }
        )
        val diskCache = ThumbnailDiskCache(
            directory = testDir,
            writer = { _, file -> file.writeBytes(ByteArray(80)); true },
            reader = { renderedBitmap }
        )
        val renderer = FakePageRenderer { renderedBitmap }
        val pipeline = ThumbnailPipeline(memCache, diskCache, renderer)

        val result = pipeline.getThumbnail(1L, "hash1", PageInfo(0, 600f, 800f))
        assertEquals(renderedBitmap, result)
        assertEquals(1, renderer.renderCallCount.get())
        assertEquals(renderedBitmap, memCache.getThumbnail(0))
        assertTrue(diskCache.contains("hash1", 0, 400))
    }

    @Test
    fun testConcurrentRequestsCoalesce() = runTest {
        val renderedBitmap = createFakeBitmap()
        val memCache = TileCache(
            budget = TileMemoryBudget(),
            bitmapSizer = { 100L },
            bitmapReleaser = {},
            isBitmapRecycled = { false }
        )
        val diskCache = ThumbnailDiskCache(
            directory = testDir,
            writer = { _, file -> file.writeBytes(ByteArray(80)); true },
            reader = { renderedBitmap }
        )

        val gate = CompletableDeferred<Unit>()
        val renderer = FakePageRenderer {
            gate.await()
            renderedBitmap
        }
        val pipeline = ThumbnailPipeline(memCache, diskCache, renderer)

        val page = PageInfo(0, 600f, 800f)
        val deferred1 = async { pipeline.getThumbnail(1L, "hash1", page) }
        val deferred2 = async { pipeline.getThumbnail(1L, "hash1", page) }

        gate.complete(Unit)

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        assertEquals(renderedBitmap, res1)
        assertEquals(renderedBitmap, res2)
        assertEquals(1, renderer.renderCallCount.get())
    }

    @Test
    fun testLoadFromDisk() = runTest {
        val diskBitmap = createFakeBitmap()
        val memCache = TileCache(
            budget = TileMemoryBudget(),
            bitmapSizer = { 100L },
            bitmapReleaser = {},
            isBitmapRecycled = { false }
        )
        val diskCache = ThumbnailDiskCache(
            directory = testDir,
            writer = { _, file -> file.writeBytes(ByteArray(50)); true },
            reader = { diskBitmap }
        )
        diskCache.put("hash1", 0, 400, diskBitmap)

        val renderer = FakePageRenderer { createFakeBitmap() }
        val pipeline = ThumbnailPipeline(memCache, diskCache, renderer)

        assertNull(pipeline.loadFromDisk("absentHash", 0))

        val loaded = pipeline.loadFromDisk("hash1", 0)
        assertNotNull(loaded)
        assertEquals(diskBitmap, loaded)
        // loadFromDisk is cold-start safe: does not pollute open document's memory cache
        assertNull(memCache.getThumbnail(0))
    }

    @Test
    fun testSwitchingDocumentClearsMemoryCache() = runTest {
        val bitmapDoc1 = createFakeBitmap()
        val bitmapDoc2 = createFakeBitmap()
        val memCache = TileCache(
            budget = TileMemoryBudget(),
            bitmapSizer = { 100L },
            bitmapReleaser = {},
            isBitmapRecycled = { false }
        )
        val diskCache = ThumbnailDiskCache(
            directory = testDir,
            writer = { _, file -> file.writeBytes(ByteArray(50)); true },
            reader = { null }
        )
        val renderer = FakePageRenderer { pageIndex ->
            if (pageIndex == 0) bitmapDoc1 else bitmapDoc2
        }
        val pipeline = ThumbnailPipeline(memCache, diskCache, renderer)

        val page0 = PageInfo(0, 600f, 800f)
        val res1 = pipeline.getThumbnail(1L, "hashDoc1", page0)
        assertEquals(bitmapDoc1, res1)
        assertEquals(bitmapDoc1, memCache.getThumbnail(0))

        // Opening document 2 clears previous document's thumbnails from memory cache
        val res2 = pipeline.getThumbnail(2L, "hashDoc2", page0)
        // Renderer is called again for doc2 instead of returning stale doc1 thumbnail
        assertEquals(2, renderer.renderCallCount.get())
    }
}
