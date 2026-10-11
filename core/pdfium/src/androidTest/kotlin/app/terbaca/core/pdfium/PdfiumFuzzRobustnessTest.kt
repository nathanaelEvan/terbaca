package app.terbaca.core.pdfium

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Random

/**
 * Instrumented fuzz and robustness tests per PRD §7 ("Crash on malformed corpus PDFs: UI process never crashes")
 * and PRD §8 (M5-01).
 *
 * Subjecting PDFium engine, JNI layer, and isolated render service to progressive truncations,
 * bit/byte corruption mutations, structural/header damage, and malformed extraction requests.
 * Every mutated document that opens is also rendered, at engine and IPC level.
 *
 * The IPC renderer runs with `autoRecover = false` and an `onProcessDied` hook, so a `:render`
 * crash fails the test instead of being retried and reported as a clean rejection.
 */
@RunWith(AndroidJUnit4::class)
class PdfiumFuzzRobustnessTest {

    private lateinit var context: Context
    private lateinit var renderer: PdfiumRemotePageRenderer
    private val tempFiles = mutableListOf<File>()

    @Volatile
    private var renderProcessDied = false

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PdfiumEngine.initialize()
        renderer = PdfiumRemotePageRenderer(
            context,
            onProcessDied = { renderProcessDied = true },
            autoRecover = false
        )
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

    private fun readAsset(assetName: String): ByteArray {
        return context.assets.open(assetName).use { it.readBytes() }
    }

    private fun writeBytesToTempFile(data: ByteArray, prefix: String = "fuzz_"): File {
        val file = File(context.cacheDir, "${prefix}${System.nanoTime()}.pdf")
        FileOutputStream(file).use { it.write(data) }
        tempFiles.add(file)
        return file
    }


    private fun assertRenderProcessAlive(case: String) {
        assertFalse("The :render process died during $case", renderProcessDied)
        assertTrue("Renderer must remain connected after $case", renderer.isServiceConnected)
    }

    /** A clean rejection is fine; an IPC failure means `:render` died, which is not. */
    private fun rethrowIfProcessDeath(e: PdfiumException) {
        if (e.cause is RemoteException || renderProcessDied) throw AssertionError("The :render process died", e)
    }

    /** Opens [pfd] in the test process and runs every per-page entry point, including a tile render. */
    private fun probeEngine(pfd: ParcelFileDescriptor) {
        var doc: PdfiumDocument? = null
        try {
            doc = PdfiumEngine.openDocument(pfd)
            assertTrue("If document opened, pageCount must be non-negative", doc.pageCount >= 0)
            if (doc.pageCount > 0) {
                assertTrue(doc.getPageSize(0).widthPoints >= 0f)
                doc.getPageText(0)
                doc.getPageCharBoxes(0)
                doc.getPageLinks(0)
                doc.getOutline()
                doc.getImageBounds(0)
                doc.renderTileToDirectBuffer(0, ByteBuffer.allocateDirect(TILE * TILE * 4), 612, 792, 0, 0, TILE, TILE)
            }
        } catch (_: PdfiumException) {
            // Expected clean rejection
        } finally {
            doc?.close()
        }
    }

    /** Same as [probeEngine] across Binder, in the isolated `:render` process. Closes [pfd]. */
    private fun probeIpc(pfd: ParcelFileDescriptor) {
        var handle: Long? = null
        try {
            handle = runBlocking { renderer.openDocument(pfd) }
            assertTrue(handle > 0L)
            val count = runBlocking { renderer.getPageCount(handle) }
            assertTrue(count >= 0)
            if (count > 0) {
                runBlocking {
                    renderer.getPageInfo(handle, 0)
                    renderer.getPageText(handle, 0)
                    renderer.getPageLinks(handle, 0)
                    renderer.getOutline(handle)
                    renderer.getImageBounds(handle, 0)
                    renderer.renderTile(handle, 0, 612, 792, 0, 0, TILE, TILE)
                }
            }
        } catch (e: PdfiumException) {
            rethrowIfProcessDeath(e)
        } finally {
            handle?.let { h -> runCatching { runBlocking { renderer.closeDocument(h) } } }
        }
    }

    private fun probeBoth(data: ByteArray, prefix: String) {
        val file = writeBytesToTempFile(data, prefix)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            probeEngine(pfd)
            probeIpc(pfd)
        }
        assertRenderProcessAlive(prefix)
    }

    @Test
    fun testNonPdfPayloads_alwaysRejectedGracefullyWithoutCrash() {
        val payloads = listOf(
            "empty" to ByteArray(0),
            "single_byte_zero" to byteArrayOf(0),
            "single_byte_percent" to byteArrayOf('%'.code.toByte()),
            "plain_text" to "This is plain text and not a PDF file.".toByteArray(),
            "html" to "<!DOCTYPE html><html><body>Not a PDF</body></html>".toByteArray(),
            "random_binary_1k" to ByteArray(1024).apply { Random(1).nextBytes(this) },
            "all_zeros_4k" to ByteArray(4096),
            "all_ones_4k" to ByteArray(4096) { 0xFF.toByte() }
        )

        for ((name, data) in payloads) {
            val file = writeBytesToTempFile(data, "non_pdf_${name}_")
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                // Native engine level
                assertThrows("Payload $name must be rejected by native engine", PdfCorruptException::class.java) {
                    PdfiumEngine.openDocument(pfd)
                }

                // IPC level
                assertThrows("Payload $name must be rejected across IPC", PdfCorruptException::class.java) {
                    runBlocking { renderer.openDocument(pfd) }
                }
            }
            assertRenderProcessAlive("non-PDF payload $name")
        }
    }

    @Test
    fun testProgressiveTruncation_nativeAndIpcNeverCrash() {
        val seedBytes = readAsset("fixtures/valid_2pages.pdf")
        val cutoffs = listOf(
            1, 2, 4, 8, 16, 32, 64, 128, 256, 512,
            seedBytes.size / 4,
            seedBytes.size / 2,
            seedBytes.size * 3 / 4,
            seedBytes.size - 50,
            seedBytes.size - 10,
            seedBytes.size - 1
        )

        for (cut in cutoffs) {
            probeBoth(seedBytes.copyOf(cut), "trunc_${cut}_")
        }
    }

    @Test
    fun testBitByteFlipping_nativeAndIpcNeverCrash() {
        val rng = Random(42)
        // A tiny text seed, and an image/stream-heavy one where decoders get exercised.
        val seeds = listOf(readAsset("fixtures/valid_2pages.pdf"), readAsset("fixtures/heavy_plot.pdf"))

        for (i in 0 until 100) {
            val seedBytes = seeds[i % 2]
            val mutated = seedBytes.clone()
            val mutationType = i % 4
            when (mutationType) {
                0 -> {
                    // Single bit flip
                    val idx = rng.nextInt(mutated.size)
                    val bit = rng.nextInt(8)
                    mutated[idx] = (mutated[idx].toInt() xor (1 shl bit)).toByte()
                }
                1 -> {
                    // Overwrite zero block
                    val idx = rng.nextInt(mutated.size)
                    val span = rng.nextInt(32.coerceAtMost(mutated.size - idx)) + 1
                    for (k in 0 until span) mutated[idx + k] = 0
                }
                2 -> {
                    // Overwrite FF block
                    val idx = rng.nextInt(mutated.size)
                    val span = rng.nextInt(32.coerceAtMost(mutated.size - idx)) + 1
                    for (k in 0 until span) mutated[idx + k] = 0xFF.toByte()
                }
                3 -> {
                    // Random noise chunk
                    val idx = rng.nextInt(mutated.size)
                    val span = rng.nextInt(64.coerceAtMost(mutated.size - idx)) + 1
                    val noise = ByteArray(span).apply { rng.nextBytes(this) }
                    System.arraycopy(noise, 0, mutated, idx, span)
                }
            }

            probeBoth(mutated, "fuzz_flip_${i}_")
        }
    }

    @Test
    fun testHeaderAndStructuralCorruption_cleanlyHandled() {
        val seedBytes = readAsset("fixtures/valid_2pages.pdf")
        val seedStr = String(seedBytes, Charsets.ISO_8859_1)

        val mutations = listOf(
            "bad_magic" to ("NOTAPDF" + seedStr.substring(7)).toByteArray(Charsets.ISO_8859_1),
            "future_version" to ("%PDF-99.9\n" + seedStr.substring(9)).toByteArray(Charsets.ISO_8859_1),
            "huge_startxref" to seedStr.replace("startxref", "startxref\n999999999\nstartxref").toByteArray(Charsets.ISO_8859_1),
            "zero_startxref" to seedStr.replace("startxref", "startxref\n0\nstartxref").toByteArray(Charsets.ISO_8859_1),
            "negative_startxref" to seedStr.replace("startxref", "startxref\n-1\nstartxref").toByteArray(Charsets.ISO_8859_1),
            "broken_root" to seedStr.replace("/Root", "/Root 9999 0 R /Fake").toByteArray(Charsets.ISO_8859_1)
        )

        for ((name, data) in mutations) {
            probeBoth(data, "struct_${name}_")
        }
    }

    @Test
    fun testTileRenderingOnCorruptAndExtremeParameters_neverAborts() {
        val file = writeBytesToTempFile(readAsset("fixtures/valid_2pages.pdf"))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val doc = PdfiumEngine.openDocument(pfd)
            try {
                val buffer = ByteBuffer.allocateDirect(256 * 256 * 4)

                // Extreme start offsets
                val status1 = doc.renderTileToDirectBuffer(
                    pageIndex = 0,
                    directBuffer = buffer,
                    fullWidth = 612,
                    fullHeight = 792,
                    startX = 5000,
                    startY = 5000,
                    tileWidth = 256,
                    tileHeight = 256
                )
                assertEquals(PdfiumDocument.STATUS_SUCCESS, status1)

                // Negative start offsets
                val status2 = doc.renderTileToDirectBuffer(
                    pageIndex = 0,
                    directBuffer = buffer,
                    fullWidth = 612,
                    fullHeight = 792,
                    startX = -100,
                    startY = -100,
                    tileWidth = 256,
                    tileHeight = 256
                )
                assertEquals(PdfiumDocument.STATUS_SUCCESS, status2)

                // Invalid page indices
                assertThrows(PdfPageNotFoundException::class.java) {
                    doc.renderTileToDirectBuffer(
                        pageIndex = -1,
                        directBuffer = buffer,
                        fullWidth = 612,
                        fullHeight = 792,
                        startX = 0,
                        startY = 0,
                        tileWidth = 256,
                        tileHeight = 256
                    )
                }
                assertThrows(PdfPageNotFoundException::class.java) {
                    doc.renderTileToDirectBuffer(
                        pageIndex = 9999,
                        directBuffer = buffer,
                        fullWidth = 612,
                        fullHeight = 792,
                        startX = 0,
                        startY = 0,
                        tileWidth = 256,
                        tileHeight = 256
                    )
                }
            } finally {
                doc.close()
            }
        }
    }

    @Test
    fun testRapidMalformedOpenCloseCycles_keepsServiceHealthy() {
        val corruptBytes = readAsset("fixtures/corrupt.pdf")
        val validBytes = readAsset("fixtures/valid_2pages.pdf")

        for (cycle in 0 until 10) {
            val fileCorrupt = writeBytesToTempFile(corruptBytes, "cycle_corrupt_${cycle}_")
            ParcelFileDescriptor.open(fileCorrupt, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                assertThrows(PdfCorruptException::class.java) {
                    runBlocking { renderer.openDocument(pfd) }
                }
            }

            // Immediately open valid document to ensure service did not hang or leak state
            val fileValid = writeBytesToTempFile(validBytes, "cycle_valid_${cycle}_")
            ParcelFileDescriptor.open(fileValid, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val handle = runBlocking { renderer.openDocument(pfd) }
                assertTrue(handle > 0L)
                assertEquals(2, runBlocking { renderer.getPageCount(handle) })
                val tile = runBlocking {
                    renderer.renderTile(
                        docHandle = handle,
                        pageIndex = 0,
                        fullWidth = 612,
                        fullHeight = 792,
                        startX = 0,
                        startY = 0,
                        tileWidth = 128,
                        tileHeight = 128
                    )
                }
                assertNotNull(tile)
                runBlocking { renderer.closeDocument(handle) }
            }
        }
    }

    private companion object {
        const val TILE = 256
    }
}
