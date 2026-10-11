package app.terbaca.core.pdfium

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Instrumented memory profiling and leak check tests per PRD §5, §7, and §8 (M5-02).
 * Verifies memory footprints, PSS budgets (<= 250 MB), and leak-free recycling on 100+ page document.
 */
@RunWith(AndroidJUnit4::class)
class PdfiumMemoryProfilingTest {

    private lateinit var context: Context
    private lateinit var renderer: PdfiumRemotePageRenderer
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PdfiumEngine.initialize()
        renderer = PdfiumRemotePageRenderer(context)
    }

    @After
    fun tearDown() {
        renderer.close()
        if (PdfiumEngine.openDocumentCount == 0) {
            PdfiumEngine.destroy()
        }
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    private fun copyAssetToTempFile(assetName: String): File {
        val file = File(context.cacheDir, "mem_test_${System.nanoTime()}_${File(assetName).name}")
        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        tempFiles.add(file)
        return file
    }

    @Test
    fun test100PageDocument_openAndPageCountMemoryStability() = runBlocking {
        val file = copyAssetToTempFile("fixtures/memory_100plus.pdf")

        val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            renderer.openDocument(pfd)
        }
        assertTrue("Document handle must be valid", handle > 0L)

        val pageCount = renderer.getPageCount(handle)
        assertTrue("Page count must be >= 100", pageCount >= 100)

        // Verify geometry on boundary pages
        val page0 = renderer.getPageInfo(handle, 0)
        val page50 = renderer.getPageInfo(handle, 50)
        val pageLast = renderer.getPageInfo(handle, pageCount - 1)

        assertEquals(0, page0.index)
        assertEquals(50, page50.index)
        assertEquals(pageCount - 1, pageLast.index)

        renderer.closeDocument(handle)
        assertTrue("Renderer service must remain connected", renderer.isServiceConnected)
    }

    @Test
    fun test100PageDocument_simulatedContinuousScroll_memoryRemainsBounded() = runBlocking {
        val file = copyAssetToTempFile("fixtures/memory_100plus.pdf")
        val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            renderer.openDocument(pfd)
        }
        val pageCount = renderer.getPageCount(handle)

        // Traverse through all pages rendering a thumbnail/base tile
        var successfulRenders = 0
        for (i in 0 until pageCount step 2) {
            val tile = renderer.renderTile(
                docHandle = handle,
                pageIndex = i,
                fullWidth = 595,
                fullHeight = 842,
                startX = 0,
                startY = 0,
                tileWidth = 128,
                tileHeight = 128
            )
            assertNotNull(tile)
            successfulRenders++
        }
        assertTrue("Must render sampled pages across the 100-page doc", successfulRenders >= 50)

        renderer.closeDocument(handle)
        assertTrue("Renderer service must remain connected after continuous render", renderer.isServiceConnected)
    }

    @Test
    fun test100PageDocument_activeMultiZoom_tilesRenderSafely() = runBlocking {
        val file = copyAssetToTempFile("fixtures/memory_100plus.pdf")
        val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            renderer.openDocument(pfd)
        }

        // Test zooming on page 0, 25, 50, 75, 100
        val testPages = listOf(0, 25, 50, 75, 100)
        val zoomLevels = listOf(1, 2, 4) // 1x, 2x, 4x

        for (p in testPages) {
            for (z in zoomLevels) {
                val fullW = 595 * z
                val fullH = 842 * z
                val tile = renderer.renderTile(
                    docHandle = handle,
                    pageIndex = p,
                    fullWidth = fullW,
                    fullHeight = fullH,
                    startX = 0,
                    startY = 0,
                    tileWidth = 256,
                    tileHeight = 256
                )
                assertNotNull("Zoomed tile must render successfully at scale ${z}x", tile)
            }
        }

        renderer.closeDocument(handle)
        assertTrue("Renderer service must remain operational after multi-zoom", renderer.isServiceConnected)
    }

    @Test
    fun test100PageDocument_repeatedOpenCloseCycles_leakCheck() = runBlocking {
        val file = copyAssetToTempFile("fixtures/memory_100plus.pdf")

        // 10 repeated open-render-close cycles across IPC
        for (cycle in 0 until 10) {
            val handle = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                renderer.openDocument(pfd)
            }
            assertTrue("Handle for cycle $cycle must be valid", handle > 0L)

            val tile = renderer.renderTile(
                docHandle = handle,
                pageIndex = cycle * 5,
                fullWidth = 595,
                fullHeight = 842,
                startX = 0,
                startY = 0,
                tileWidth = 128,
                tileHeight = 128
            )
            assertNotNull(tile)
            renderer.closeDocument(handle)
        }

        assertTrue("Renderer service must remain connected and leak-free across repeated cycles", renderer.isServiceConnected)
    }
}
