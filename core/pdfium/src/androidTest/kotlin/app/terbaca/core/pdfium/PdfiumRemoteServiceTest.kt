package app.terbaca.core.pdfium

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.Process


import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PdfiumRemoteServiceTest {

    private companion object {
        const val MIN_MEASURABLE_RENDER_MS = 40L
    }


    private lateinit var context: Context
    private lateinit var renderer: PdfiumRemotePageRenderer
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        renderer = PdfiumRemotePageRenderer(context)
    }

    @After
    fun tearDown() {
        renderer.close()
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    private fun copyAssetToTempFile(assetName: String): File {
        val file = File(context.cacheDir, "remote_test_${System.nanoTime()}_${File(assetName).name}")
        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        tempFiles.add(file)
        return file
    }

    @Test
    fun testRemoteRenderService_validDocumentGeometry() {
        runBlocking {
            val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue(handle > 0L)
            assertTrue(renderer.isServiceConnected)

            val pageCount = renderer.getPageCount(handle)
            assertEquals(2, pageCount)

            val page0 = renderer.getPageInfo(handle, 0)
            assertEquals(0, page0.index)
            assertEquals(612f, page0.widthPoints, 1f)
            assertEquals(792f, page0.heightPoints, 1f)

            val page1 = renderer.getPageInfo(handle, 1)
            assertEquals(1, page1.index)
            assertEquals(595f, page1.widthPoints, 1f)
            assertEquals(842f, page1.heightPoints, 1f)

            val allPages = renderer.getAllPages(handle)
            assertEquals(2, allPages.size)

            renderer.closeDocument(handle)
        }
    }

    @Test
    fun testRemoteRenderService_passwordProtectedDocument() {
        val file = copyAssetToTempFile("fixtures/password_protected.pdf")

        // Without password
        assertThrows(PdfPasswordException::class.java) {
            runBlocking {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    renderer.openDocument(pfd)
                }
            }
        }

        // With correct password
        runBlocking {
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd, password = "secret123")
            }
            assertTrue(handle > 0L)
            assertEquals(1, renderer.getPageCount(handle))
            renderer.closeDocument(handle)
        }
    }

    @Test
    fun testRemoteRenderService_corruptDocument() {
        val file = copyAssetToTempFile("fixtures/corrupt.pdf")
        assertThrows(PdfCorruptException::class.java) {
            runBlocking {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    renderer.openDocument(pfd)
                }
            }
        }
    }

    @Test
    fun testRemoteRenderService_invalidPageIndex_throwsTypedException() {
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            runBlocking { renderer.openDocument(pfd) }
        }
        try {
            // Negative page index
            assertThrows(PdfPageNotFoundException::class.java) {
                runBlocking {
                    renderer.getPageInfo(handle, -1)
                }
            }

            // Out-of-bounds page index (valid_2pages.pdf has 2 pages: 0 and 1)
            assertThrows(PdfPageNotFoundException::class.java) {
                runBlocking {
                    renderer.getPageInfo(handle, 999)
                }
            }

            // Ensure :render did not crash and is still connected and operational
            assertTrue(renderer.isServiceConnected)
            val pageCount = runBlocking { renderer.getPageCount(handle) }
            assertEquals(2, pageCount)
        } finally {
            runBlocking { renderer.closeDocument(handle) }
        }
    }

    @Test
    fun testRemoteRenderService_unknownHandle_throwsTypedException() {
        // Unknown handle passed to getPageCount
        assertThrows(PdfInvalidHandleException::class.java) {
            runBlocking {
                renderer.getPageCount(888_888_888L)
            }
        }

        // Unknown handle passed to getPageInfo
        assertThrows(PdfInvalidHandleException::class.java) {
            runBlocking {
                renderer.getPageInfo(888_888_888L, 0)
            }
        }

        // Ensure :render did not crash and is still operational for valid documents
        assertTrue(renderer.isServiceConnected)
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            runBlocking { renderer.openDocument(pfd) }
        }
        try {
            val count = runBlocking { renderer.getPageCount(handle) }
            assertEquals(2, count)
        } finally {
            runBlocking { renderer.closeDocument(handle) }
        }
    }

    @Test
    fun testRemoteRenderService_processKillAndRecovery() {
        val deathCount = AtomicInteger(0)
        val deathLatch = CountDownLatch(1)
        val testRenderer = PdfiumRemotePageRenderer(context) {
            deathCount.incrementAndGet()
            deathLatch.countDown()
        }

        try {
            val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
            val oldHandle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                runBlocking { testRenderer.openDocument(pfd) }
            }
            assertTrue(oldHandle > 0L)
            assertTrue(testRenderer.isServiceConnected)

            // Locate the :render process pid
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val renderProcess = activityManager.runningAppProcesses?.find { it.processName.endsWith(":render") }
            assertNotNull("Render process must be running", renderProcess)
            val renderPid = renderProcess!!.pid

            // Kill the :render process
            Process.killProcess(renderPid)

            // Assert that death callback fires
            val died = deathLatch.await(5, TimeUnit.SECONDS)
            assertTrue("Death callback must fire within timeout", died)

            // Give a short moment and assert death callback only fired once
            Thread.sleep(200)
            assertEquals("Death callback must fire exactly once", 1, deathCount.get())

            // Trigger rebind to spawn and connect to a fresh :render process
            testRenderer.rebind()

            // Assert that the old handle fails cleanly with a typed exception
            assertThrows(PdfInvalidHandleException::class.java) {
                runBlocking {
                    testRenderer.getPageCount(oldHandle)
                }
            }

            // Assert that a new open works after the rebind
            val file2 = copyAssetToTempFile("fixtures/valid_2pages.pdf")
            val newHandle = ParcelFileDescriptor.open(file2, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                runBlocking { testRenderer.openDocument(pfd) }
            }
            assertTrue("New handle must be valid and positive", newHandle > 0L)
            assertTrue("New handle must not match old stale handle", newHandle != oldHandle)
            val pageCount = runBlocking { testRenderer.getPageCount(newHandle) }
            assertEquals(2, pageCount)
            runBlocking { testRenderer.closeDocument(newHandle) }
        } finally {
            testRenderer.close()
        }
    }

    @Test
    fun testRemoteRenderService_renderTileHardwareBuffer() {
        runBlocking {
            val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue(handle > 0L)

            val bitmap = renderer.renderTile(
                docHandle = handle,
                pageIndex = 0,
                fullWidth = 612,
                fullHeight = 792,
                startX = 0,
                startY = 0,
                tileWidth = 512,
                tileHeight = 512
            )

            assertNotNull("Rendered bitmap must not be null", bitmap)
            assertEquals("Rendered bitmap must have HARDWARE config", Bitmap.Config.HARDWARE, bitmap.config)
            assertEquals(512, bitmap.width)
            assertEquals(512, bitmap.height)

            // Verify pixel contents by copying HARDWARE bitmap to CPU-accessible ARGB_8888
            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            assertNotNull("Software copy of hardware bitmap must not be null", softwareBitmap)
            try {
                val pixel = softwareBitmap!!.getPixel(10, 10)
                assertEquals("Alpha must be fully opaque", 255, Color.alpha(pixel))
                // Background of valid_2pages.pdf is white
                assertEquals("Red channel must be white", 255, Color.red(pixel))
                assertEquals("Green channel must be white", 255, Color.green(pixel))
                assertEquals("Blue channel must be white", 255, Color.blue(pixel))
            } finally {
                softwareBitmap?.recycle()
            }

            renderer.closeDocument(handle)
        }
    }

    @Test
    fun testRemoteRenderService_pixelColor_hardwareBuffer() {
        runBlocking {
            val file = copyAssetToTempFile("fixtures/red_rectangle.pdf")
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue(handle > 0L)

            // red_rectangle.pdf is 200x200 pt with a pure-red rect [50, 50, 150, 150] on white background
            val bitmap = renderer.renderTile(
                docHandle = handle,
                pageIndex = 0,
                fullWidth = 200,
                fullHeight = 200,
                startX = 0,
                startY = 0,
                tileWidth = 200,
                tileHeight = 200,
                forceFallback = false
            )

            assertNotNull("Rendered bitmap must not be null", bitmap)
            assertEquals("Rendered bitmap must have HARDWARE config", Bitmap.Config.HARDWARE, bitmap.config)

            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            assertNotNull("Software copy of hardware bitmap must not be null", softwareBitmap)
            try {
                // (100, 100) is inside the red rectangle -> pure red (R=255, G=0, B=0)
                val insidePixel = softwareBitmap!!.getPixel(100, 100)
                assertEquals("Alpha inside must be 255", 255, Color.alpha(insidePixel))
                assertEquals("Red inside must be 255 (RGBA byte order)", 255, Color.red(insidePixel))
                assertEquals("Green inside must be 0", 0, Color.green(insidePixel))
                assertEquals("Blue inside must be 0", 0, Color.blue(insidePixel))

                // (10, 10) is outside the red rectangle -> pure white (R=255, G=255, B=255)
                val outsidePixel = softwareBitmap.getPixel(10, 10)
                assertEquals("Alpha outside must be 255", 255, Color.alpha(outsidePixel))
                assertEquals("Red outside must be 255", 255, Color.red(outsidePixel))
                assertEquals("Green outside must be 255", 255, Color.green(outsidePixel))
                assertEquals("Blue outside must be 255", 255, Color.blue(outsidePixel))
            } finally {
                softwareBitmap?.recycle()
            }

            renderer.closeDocument(handle)
        }
    }

    @Test
    fun testRemoteRenderService_pixelColor_forceFallback() {
        runBlocking {
            val file = copyAssetToTempFile("fixtures/red_rectangle.pdf")
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue(handle > 0L)

            // Render via SharedMemory fallback path
            val bitmap = renderer.renderTile(
                docHandle = handle,
                pageIndex = 0,
                fullWidth = 200,
                fullHeight = 200,
                startX = 0,
                startY = 0,
                tileWidth = 200,
                tileHeight = 200,
                forceFallback = true
            )

            assertNotNull("Fallback bitmap must not be null", bitmap)
            assertEquals("Fallback bitmap must be promoted to HARDWARE config", Bitmap.Config.HARDWARE, bitmap.config)

            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            assertNotNull("Software copy of fallback bitmap must not be null", softwareBitmap)
            try {
                // (100, 100) is inside the red rectangle -> pure red (R=255, G=0, B=0)
                val insidePixel = softwareBitmap!!.getPixel(100, 100)
                assertEquals("Alpha inside must be 255", 255, Color.alpha(insidePixel))
                assertEquals("Red inside must be 255 (RGBA byte order)", 255, Color.red(insidePixel))
                assertEquals("Green inside must be 0", 0, Color.green(insidePixel))
                assertEquals("Blue inside must be 0", 0, Color.blue(insidePixel))

                // (10, 10) is outside the red rectangle -> pure white (R=255, G=255, B=255)
                val outsidePixel = softwareBitmap.getPixel(10, 10)
                assertEquals("Alpha outside must be 255", 255, Color.alpha(outsidePixel))
                assertEquals("Red outside must be 255", 255, Color.red(outsidePixel))
                assertEquals("Green outside must be 255", 255, Color.green(outsidePixel))
                assertEquals("Blue outside must be 255", 255, Color.blue(outsidePixel))
            } finally {
                softwareBitmap?.recycle()
            }

            renderer.closeDocument(handle)
        }
    }

    @Test
    fun testRemoteRenderService_renderTileSubRectangle() {
        runBlocking {
            val file = copyAssetToTempFile("fixtures/red_rectangle.pdf")
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue(handle > 0L)

            // Red rectangle is at [50, 50, 150, 150] on 200x200 page.
            // Rendering sub-rectangle tile shifted by (startX=50, startY=50) of size 100x100:
            // Pixel (0, 0) in the shifted tile corresponds to (50, 50) on page (the top-left of the red rect).
            val shiftedTile = renderer.renderTile(
                docHandle = handle,
                pageIndex = 0,
                fullWidth = 200,
                fullHeight = 200,
                startX = 50,
                startY = 50,
                tileWidth = 100,
                tileHeight = 100
            )

            assertNotNull("Shifted tile bitmap must not be null", shiftedTile)
            assertEquals(100, shiftedTile.width)
            assertEquals(100, shiftedTile.height)

            val softwareShifted = shiftedTile.copy(Bitmap.Config.ARGB_8888, false)
            assertNotNull(softwareShifted)
            try {
                // (0, 0) in shifted tile maps to (50, 50) on page -> inside red rect
                val p00 = softwareShifted!!.getPixel(0, 0)
                assertEquals("Pixel (0,0) of tile shifted to (50,50) must be red", 255, Color.red(p00))
                assertEquals(0, Color.green(p00))
                assertEquals(0, Color.blue(p00))

                // (50, 50) in shifted tile maps to (100, 100) on page -> inside red rect
                val p50 = softwareShifted.getPixel(50, 50)
                assertEquals("Pixel (50,50) of tile shifted to (50,50) must be red", 255, Color.red(p50))
                assertEquals(0, Color.green(p50))
                assertEquals(0, Color.blue(p50))
            } finally {
                softwareShifted?.recycle()
            }

            // In contrast, unshifted tile at (startX=0, startY=0):
            // Pixel (0, 0) corresponds to (0, 0) on page -> white background
            val unshiftedTile = renderer.renderTile(
                docHandle = handle,
                pageIndex = 0,
                fullWidth = 200,
                fullHeight = 200,
                startX = 0,
                startY = 0,
                tileWidth = 100,
                tileHeight = 100
            )
            val softwareUnshifted = unshiftedTile.copy(Bitmap.Config.ARGB_8888, false)
            assertNotNull(softwareUnshifted)
            try {
                val p00 = softwareUnshifted!!.getPixel(0, 0)
                assertEquals("Pixel (0,0) of unshifted tile must be white", 255, Color.red(p00))
                assertEquals(255, Color.green(p00))
                assertEquals(255, Color.blue(p00))
            } finally {
                softwareUnshifted?.recycle()
            }

            renderer.closeDocument(handle)
        }
    }

    @Test
    fun testRemoteRenderService_oversizedTileRequest_returnsErrorWithoutCrashing() {
        runBlocking {
            val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue(handle > 0L)

            // Request tile with dimension > 4096
            assertThrows(PdfiumException::class.java) {
                runBlocking {
                    renderer.renderTile(
                        docHandle = handle,
                        pageIndex = 0,
                        fullWidth = 612,
                        fullHeight = 792,
                        startX = 0,
                        startY = 0,
                        tileWidth = 8192,
                        tileHeight = 512
                    )
                }
            }

            // Ensure the :render service process is still alive and operational
            assertTrue("Render service must remain connected after oversized tile error", renderer.isServiceConnected)
            val validTile = renderer.renderTile(
                docHandle = handle,
                pageIndex = 0,
                fullWidth = 612,
                fullHeight = 792,
                startX = 0,
                startY = 0,
                tileWidth = 256,
                tileHeight = 256
            )
            assertNotNull("Subsequent valid tile must succeed", validTile)
            assertEquals(256, validTile.width)
            assertEquals(256, validTile.height)

            renderer.closeDocument(handle)
        }
    }


    @Test
    fun testRemoteRenderService_renderTileInvalidPageThrows() {
        runBlocking {
            val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue(handle > 0L)

            assertThrows(PdfPageNotFoundException::class.java) {
                runBlocking {
                    renderer.renderTile(
                        docHandle = handle,
                        pageIndex = 99,
                        fullWidth = 612,
                        fullHeight = 792,
                        startX = 0,
                        startY = 0,
                        tileWidth = 512,
                        tileHeight = 512
                    )
                }
            }

            renderer.closeDocument(handle)
        }
    }

    @Test
    fun testRemoteRenderService_renderTileInvalidHandleThrows() {
        assertThrows(PdfInvalidHandleException::class.java) {
            runBlocking {
                renderer.renderTile(
                    docHandle = 999999L,
                    pageIndex = 0,
                    fullWidth = 612,
                    fullHeight = 792,
                    startX = 0,
                    startY = 0,
                    tileWidth = 512,
                    tileHeight = 512
                )
            }
        }
    }

    @Test
    fun testRemoteRenderService_inFlightCancellation_viaCancelTile() {
        runBlocking {
            val file = copyAssetToTempFile("fixtures/heavy_plot.pdf")
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue(handle > 0L)

            val renderId = 8888L
            var caughtException: Throwable? = null
            val renderLatch = CountDownLatch(1)

            val renderJob = launch(Dispatchers.IO) {
                try {
                    renderer.renderTile(
                        renderId = renderId,
                        docHandle = handle,
                        pageIndex = 0,
                        fullWidth = 2448,
                        fullHeight = 3168,
                        startX = 0,
                        startY = 0,
                        tileWidth = 1024,
                        tileHeight = 1024,
                        forceFallback = false
                    )
                } catch (t: Throwable) {
                    caughtException = t
                } finally {
                    renderLatch.countDown()
                }
            }

            // Immediately cancel the tile
            renderer.cancelTile(renderId)

            val finished = renderLatch.await(5, TimeUnit.SECONDS)
            assertTrue("Cancelled render must complete promptly", finished)
            renderJob.join()

            assertNotNull("Render must throw an exception on cancellation", caughtException)
            assertTrue(
                "Caught exception must be PdfRenderCancelledException, was: $caughtException",
                caughtException is PdfRenderCancelledException
            )

            // Render service must remain connected and operational
            assertTrue("Render service must remain connected after cancellation", renderer.isServiceConnected)

            // Subsequent tile must succeed without falling back to software/SharedMemory
            val validTile = renderer.renderTile(
                docHandle = handle,
                pageIndex = 0,
                fullWidth = 612,
                fullHeight = 792,
                startX = 0,
                startY = 0,
                tileWidth = 256,
                tileHeight = 256
            )
            assertNotNull("Subsequent tile must succeed", validTile)
            assertEquals("Hardware transport must still be active", Bitmap.Config.HARDWARE, validTile.config)

            renderer.closeDocument(handle)
        }
    }

    @Test
    fun testRemoteRenderService_inFlightCancellation_viaCoroutineCancellation() {
        runBlocking {
            val file = copyAssetToTempFile("fixtures/heavy_plot.pdf")
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue(handle > 0L)

            // Measure the full, uncancelled render of the heavy vector plot. Render twice and keep
            // the second (warm) timing, so the cancelled run below can't look faster just because
            // page data is already loaded.
            var fullDuration = 0L
            repeat(2) {
                val fullStart = System.currentTimeMillis()
                val fullTile = renderer.renderTile(
                    docHandle = handle,
                    pageIndex = 0,
                    fullWidth = 2448,
                    fullHeight = 3168,
                    startX = 0,
                    startY = 0,
                    tileWidth = 1024,
                    tileHeight = 1024
                )
                fullDuration = System.currentTimeMillis() - fullStart
                assertNotNull(fullTile)
                fullTile.recycle()
            }
            org.junit.Assume.assumeTrue(
                "Heavy render too fast ($fullDuration ms) to measure cancellation on this device",
                fullDuration >= MIN_MEASURABLE_RENDER_MS
            )

            val renderJob = launch(Dispatchers.IO) {
                try {
                    renderer.renderTile(
                        docHandle = handle,
                        pageIndex = 0,
                        fullWidth = 2448,
                        fullHeight = 3168,
                        startX = 0,
                        startY = 0,
                        tileWidth = 1024,
                        tileHeight = 1024
                    )
                } catch (e: CancellationException) {
                    // Normal coroutine cancellation
                }
            }

            // Let coroutine start rendering, then cancel and measure time to return
            delay(5)
            val cancelStart = System.currentTimeMillis()
            renderJob.cancelAndJoin()
            val cancelDuration = System.currentTimeMillis() - cancelStart

            assertTrue("Render job must be cancelled", renderJob.isCancelled)
            // If cancellation didn't reach PDFium, cancelAndJoin would wait for the rest of the
            // render (~fullDuration - 5 ms). Requiring under half rules that out.
            assertTrue(
                "Cancelled render must stop early (cancelDuration=$cancelDuration ms, fullDuration=$fullDuration ms)",
                cancelDuration < fullDuration / 2
            )

            // Service must still be connected
            assertTrue("Render service must remain connected after job cancellation", renderer.isServiceConnected)

            // Subsequent tile must succeed
            val validTile = renderer.renderTile(
                docHandle = handle,
                pageIndex = 0,
                fullWidth = 612,
                fullHeight = 792,
                startX = 0,
                startY = 0,
                tileWidth = 256,
                tileHeight = 256
            )
            assertNotNull("Subsequent tile must succeed", validTile)
            assertEquals(256, validTile.width)

            renderer.closeDocument(handle)
        }
    }

    @Test
    fun testRemoteRenderService_cancelAllTiles() {
        runBlocking {
            val file = copyAssetToTempFile("fixtures/heavy_plot.pdf")
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue(handle > 0L)

            val latch = CountDownLatch(1)

            launch(Dispatchers.IO) {
                try {
                    renderer.renderTile(
                        docHandle = handle,
                        pageIndex = 0,
                        fullWidth = 2448,
                        fullHeight = 3168,
                        startX = 0,
                        startY = 0,
                        tileWidth = 1024,
                        tileHeight = 1024
                    )
                } catch (_: Throwable) {
                    // Ignored
                } finally {
                    latch.countDown()
                }
            }

            renderer.cancelAllTiles(handle)
            val finished = latch.await(5, TimeUnit.SECONDS)
            assertTrue("Render must complete after cancelAllTiles", finished)

            // Service remains healthy and subsequent render succeeds
            assertTrue(renderer.isServiceConnected)
            val tile = renderer.renderTile(
                docHandle = handle,
                pageIndex = 0,
                fullWidth = 612,
                fullHeight = 792,
                startX = 0,
                startY = 0,
                tileWidth = 256,
                tileHeight = 256
            )
            assertNotNull(tile)

            renderer.closeDocument(handle)
        }
    }
}

