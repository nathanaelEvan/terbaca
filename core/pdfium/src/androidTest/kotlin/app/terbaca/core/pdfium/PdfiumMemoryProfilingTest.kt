package app.terbaca.core.pdfium

import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Memory checks on the 100+ page document (PRD §5, §7, M5-02).
 *
 * The leak check measures the `:render` process PSS through `dumpsys meminfo`, before and after
 * repeated open/render/close cycles. It also asserts the process was not restarted in between,
 * so a crash plus recovery cannot hide a leak. The §7 total-PSS budget (UI + render) is measured
 * on device with `tools/perf/measure_pss.sh`, not here.
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
        renderer = PdfiumRemotePageRenderer(context, autoRecover = false)
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

    private suspend fun open(file: File): Long =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            renderer.openDocument(pfd)
        }

    private fun renderPid(): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        val proc = am.runningAppProcesses.orEmpty()
            .firstOrNull { it.uid == Process.myUid() && it.processName.endsWith(":render") }
        assertNotNull("The :render process must be running", proc)
        return proc!!.pid
    }

    /** Total PSS of [pid] in KB, from `dumpsys meminfo` (not rate-limited like getProcessMemoryInfo). */
    private fun pssKb(pid: Int): Long {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys meminfo $pid")
        val out = ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        val match = TOTAL_PSS.find(out)
        assertNotNull("No TOTAL PSS line in dumpsys meminfo output:\n$out", match)
        return match!!.groupValues[1].toLong()
    }

    /** One open → render a few 2x tiles → close cycle, the pattern a reader session leaves behind. */
    private suspend fun openRenderCloseCycle(file: File, cycle: Int) {
        val handle = open(file)
        assertTrue("Handle for cycle $cycle must be valid", handle > 0L)
        for (page in listOf(cycle % 100, (cycle * 7 + 3) % 100)) {
            val tile = renderer.renderTile(
                docHandle = handle,
                pageIndex = page,
                fullWidth = 1190,
                fullHeight = 1684,
                startX = 0,
                startY = 0,
                tileWidth = 512,
                tileHeight = 512
            )
            assertNotNull(tile)
        }
        // NEGATIVE CONTROL (temporary): leak the document on purpose; the leak test must fail.
    }

    @Test
    fun test100PageDocument_openAndPageGeometry() = runBlocking {
        val file = copyAssetToTempFile("fixtures/memory_100plus.pdf")
        val handle = open(file)
        assertTrue("Document handle must be valid", handle > 0L)

        val pageCount = renderer.getPageCount(handle)
        assertTrue("Page count must be >= 100", pageCount >= 100)

        assertEquals(0, renderer.getPageInfo(handle, 0).index)
        assertEquals(50, renderer.getPageInfo(handle, 50).index)
        assertEquals(pageCount - 1, renderer.getPageInfo(handle, pageCount - 1).index)

        renderer.closeDocument(handle)
    }

    @Test
    fun test100PageDocument_continuousScrollAndMultiZoom_renderPssBounded() = runBlocking {
        val file = copyAssetToTempFile("fixtures/memory_100plus.pdf")
        val handle = open(file)
        val pid = renderPid()
        val pageCount = renderer.getPageCount(handle)

        for (i in 0 until pageCount step 2) {
            assertNotNull(renderer.renderTile(handle, i, 595, 842, 0, 0, 128, 128))
        }
        for (p in listOf(0, 25, 50, 75, 100)) {
            for (z in listOf(1, 2, 4)) {
                assertNotNull(
                    "Tile must render on page $p at ${z}x",
                    renderer.renderTile(handle, p, 595 * z, 842 * z, 0, 0, 256, 256)
                )
            }
        }

        val pss = pssKb(pid)
        Log.i(TAG, "render PSS after scroll + zoom: $pss KB")
        assertEquals("The :render process must not have restarted", pid, renderPid())
        assertTrue("render PSS $pss KB exceeds $RENDER_PSS_CEILING_KB KB", pss < RENDER_PSS_CEILING_KB)

        renderer.closeDocument(handle)
    }

    @Test
    fun test100PageDocument_repeatedOpenCloseCycles_renderPssDoesNotGrow() = runBlocking {
        val file = copyAssetToTempFile("fixtures/memory_100plus.pdf")

        // Warm up allocator pools, font caches and the Binder path before the baseline sample.
        repeat(WARMUP_CYCLES) { openRenderCloseCycle(file, it) }
        val pid = renderPid()
        val before = pssKb(pid)

        repeat(MEASURED_CYCLES) { openRenderCloseCycle(file, WARMUP_CYCLES + it) }
        val after = pssKb(pid)

        Log.i(TAG, "render PSS before=$before KB after=$after KB delta=${after - before} KB over $MEASURED_CYCLES cycles")
        assertEquals("The :render process must not have restarted", pid, renderPid())
        assertTrue(
            "render PSS grew ${after - before} KB over $MEASURED_CYCLES open/close cycles (limit $LEAK_LIMIT_KB KB)",
            after - before < LEAK_LIMIT_KB
        )
    }

    private companion object {
        const val TAG = "PdfiumMemoryTest"
        const val WARMUP_CYCLES = 3
        const val MEASURED_CYCLES = 20
        const val LEAK_LIMIT_KB = 12 * 1024L

        // The render process holds no tile cache (that lives in the UI process), so it stays far
        // below the §7 total; this catches runaway native growth, not the budget itself.
        const val RENDER_PSS_CEILING_KB = 150 * 1024L
        val TOTAL_PSS = Regex("""TOTAL(?: PSS:)?\s+(\d+)""")
    }
}
