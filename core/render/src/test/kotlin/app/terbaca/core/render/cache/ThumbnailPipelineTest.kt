package app.terbaca.core.render.cache

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
        val beforeRender: suspend (docHandle: Long, tileWidth: Int, tileHeight: Int) -> Unit = { _, _, _ -> },
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
            beforeRender(docHandle, tileWidth, tileHeight)
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

    private fun memoryCache() = TileCache(
        budget = TileMemoryBudget(),
        bitmapSizer = { 100L },
        bitmapReleaser = {},
        isBitmapRecycled = { false }
    )

    private fun diskCache() = ThumbnailDiskCache(
        directory = testDir,
        writer = { _, file -> file.writeBytes(ByteArray(50)); true },
        reader = { null }
    )

    @Test
    fun testTallPageRendersWithinRendererLimits() = runTest {
        val rendered = createFakeBitmap()
        val renderer = FakePageRenderer(
            beforeRender = { _, w, h ->
                // Mirrors the render service, which rejects tiles over 4096 px.
                require(w in 1..4096 && h in 1..4096) { "Tile too large: $w x $h" }
            }
        ) { rendered }
        val pipeline = ThumbnailPipeline(memoryCache(), diskCache(), renderer)

        val strip = PageInfo(0, 100f, 2000f) // 20:1, 8000 px tall at 400 px wide uncapped
        assertEquals(rendered, pipeline.getThumbnail(1L, "hash1", strip))
    }

    @Test
    fun testRendererFailureReturnsNullAndCachesNothing() = runTest {
        val memCache = memoryCache()
        val disk = diskCache()
        val renderer = FakePageRenderer { throw IllegalArgumentException("render failed") }
        val pipeline = ThumbnailPipeline(memCache, disk, renderer)

        assertNull(pipeline.getThumbnail(1L, "hash1", PageInfo(0, 600f, 800f)))
        assertNull(memCache.getThumbnail(0))
        assertFalse(disk.contains("hash1", 0, 400))

        // The failed render doesn't stay coalesced: the next request renders again.
        assertNull(pipeline.getThumbnail(1L, "hash1", PageInfo(0, 600f, 800f)))
        assertEquals(2, renderer.renderCallCount.get())
    }

    @Test
    fun testLateRenderForPreviousDocumentDoesNotLandInMemory() = runTest {
        val memCache = memoryCache()
        val renderStartedA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val renderer = FakePageRenderer(
            beforeRender = { docHandle, _, _ ->
                if (docHandle == 1L) {
                    renderStartedA.complete(Unit)
                    releaseA.await()
                }
            }
        ) { createFakeBitmap() }
        val pipeline = ThumbnailPipeline(memCache, diskCache(), renderer)
        val page0 = PageInfo(0, 600f, 800f)

        val pendingA = async { pipeline.getThumbnail(1L, "hashA", page0) }
        renderStartedA.await()
        // Document B is opened while A's render is still in flight.
        val resultB = pipeline.getThumbnail(2L, "hashB", page0)
        val cachedB = memCache.getThumbnail(0)
        releaseA.complete(Unit)
        val resultA = pendingA.await()

        assertNotNull(resultA)
        assertNotNull(resultB)
        assertEquals(resultB, cachedB)
        assertTrue(resultA !== resultB)
        assertEquals("A's late render must not replace B's thumbnail", resultB, memCache.getThumbnail(0))
    }

    @Test
    fun testCancelledFirstCallerDoesNotBlankOtherWaiters() = runTest {
        val rendered = createFakeBitmap()
        val renderStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val renderer = FakePageRenderer(
            beforeRender = { _, _, _ ->
                renderStarted.complete(Unit)
                gate.await()
            }
        ) { rendered }
        val pipeline = ThumbnailPipeline(memoryCache(), diskCache(), renderer)
        val page = PageInfo(0, 600f, 800f)

        val first = async { pipeline.getThumbnail(1L, "hash1", page) }
        renderStarted.await()
        val second = async { pipeline.getThumbnail(1L, "hash1", page) }
        // Wait (in real time; the disk read hops to Dispatchers.IO) until both callers share the render.
        withContext(Dispatchers.Default) {
            while (pipeline.inFlightWaiters < 2) delay(1)
        }

        first.cancel()
        gate.complete(Unit)

        assertEquals(rendered, second.await())
        assertTrue(first.isCancelled)
        assertEquals(1, renderer.renderCallCount.get())
    }

    @Test
    fun testRenderIsCancelledWhenEveryWaiterLeaves() = runTest {
        val renderStarted = CompletableDeferred<Unit>()
        val renderCancelled = CompletableDeferred<Unit>()
        val renderer = FakePageRenderer(
            beforeRender = { _, _, _ ->
                renderStarted.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    renderCancelled.complete(Unit)
                    throw e
                }
            }
        ) { createFakeBitmap() }
        val pipeline = ThumbnailPipeline(memoryCache(), diskCache(), renderer)

        val only = async { pipeline.getThumbnail(1L, "hash1", PageInfo(0, 600f, 800f)) }
        renderStarted.await()
        only.cancel()

        renderCancelled.await()
        assertEquals(0, pipeline.inFlightWaiters)
    }
}
