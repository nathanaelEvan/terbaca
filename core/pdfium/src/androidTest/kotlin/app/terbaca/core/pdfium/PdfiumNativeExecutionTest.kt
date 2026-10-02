package app.terbaca.core.pdfium

import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.nio.ByteBuffer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PdfiumNativeExecutionTest {

    private lateinit var context: Context
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PdfiumEngine.initialize()
    }

    @After
    fun tearDown() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
        if (PdfiumEngine.openDocumentCount == 0) {
            PdfiumEngine.destroy()
        }
    }

    private fun copyAssetToTempFile(assetName: String): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}_${File(assetName).name}")
        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        tempFiles.add(file)
        return file
    }

    @Test
    fun testValidMultiPagePdf_loadsCountAndSizesCorrectly() {
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val document = PdfiumEngine.openDocument(pfd)
            try {
                assertEquals(2, document.pageCount)

                val page0 = document.getPageSize(0)
                assertEquals(0, page0.index)
                assertEquals(612f, page0.widthPoints, 1f)
                assertEquals(792f, page0.heightPoints, 1f)

                val page1 = document.getPageSize(1)
                assertEquals(1, page1.index)
                assertEquals(595f, page1.widthPoints, 1f)
                assertEquals(842f, page1.heightPoints, 1f)

                val allPages = document.getAllPages()
                assertEquals(2, allPages.size)
                assertEquals(page0, allPages[0])
                assertEquals(page1, allPages[1])
            } finally {
                document.close()
            }
        }
    }

    @Test
    fun testPasswordProtectedPdf_withWrongPasswordThrowsPdfPasswordException() {
        val file = copyAssetToTempFile("fixtures/password_protected.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            assertThrows(PdfPasswordException::class.java) {
                PdfiumEngine.openDocument(pfd, password = "wrong_password")
            }
        }
    }

    @Test
    fun testPasswordProtectedPdf_withoutPasswordThrowsPdfPasswordException() {
        val file = copyAssetToTempFile("fixtures/password_protected.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            assertThrows(PdfPasswordException::class.java) {
                PdfiumEngine.openDocument(pfd, password = null)
            }
        }
    }

    @Test
    fun testPasswordProtectedPdf_withCorrectPasswordOpensSuccessfully() {
        val file = copyAssetToTempFile("fixtures/password_protected.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val document = PdfiumEngine.openDocument(pfd, password = "secret123")
            try {
                assertEquals(1, document.pageCount)
                val page0 = document.getPageSize(0)
                assertEquals(612f, page0.widthPoints, 1f)
            } finally {
                document.close()
            }
        }
    }

    @Test
    fun testCorruptPdf_throwsPdfCorruptException() {
        val file = copyAssetToTempFile("fixtures/corrupt.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            assertThrows(PdfCorruptException::class.java) {
                PdfiumEngine.openDocument(pfd)
            }
        }
    }

    @Test
    fun testEmptyFile_throwsPdfCorruptException() {
        val file = copyAssetToTempFile("fixtures/empty.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            assertThrows(PdfCorruptException::class.java) {
                PdfiumEngine.openDocument(pfd)
            }
        }
    }

    @Test
    fun testOpenDocumentTracksActiveDocuments() {
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val doc1 = PdfiumEngine.openDocument(pfd)
            assertEquals(1, PdfiumEngine.openDocumentCount)

            assertThrows(IllegalStateException::class.java) {
                PdfiumEngine.destroy()
            }

            doc1.close()
            assertEquals(0, PdfiumEngine.openDocumentCount)
        }
    }

    @Test
    fun testConcurrentDestroyNeverInvalidatesOpenedDocument() {
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        val failure = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val destroyer = Thread {
            start.await()
            repeat(STRESS_ITERATIONS) {
                try {
                    PdfiumEngine.destroy()
                } catch (_: IllegalStateException) {
                    // Expected while a document is open.
                }
            }
        }
        destroyer.start()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            start.countDown()
            try {
                repeat(STRESS_ITERATIONS) {
                    PdfiumEngine.openDocument(pfd).use { doc ->
                        assertEquals(2, doc.pageCount)
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        destroyer.join()
        failure.get()?.let { throw it }
        assertEquals(0, PdfiumEngine.openDocumentCount)
    }

    @Test
    fun testNativeRenderTileToHardwareBuffer_rendersSuccessfully() {
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfiumEngine.openDocument(pfd).use { doc ->
                val usage = HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                val hwBuffer = HardwareBuffer.create(
                    512,
                    512,
                    HardwareBuffer.RGBA_8888,
                    1,
                    usage
                )
                try {
                    val status = doc.renderTileToHardwareBuffer(
                        pageIndex = 0,
                        hardwareBuffer = hwBuffer,
                        fullWidth = 612,
                        fullHeight = 792,
                        startX = 0,
                        startY = 0,
                        tileWidth = 512,
                        tileHeight = 512
                    )
                    assertEquals("Native rendering to HardwareBuffer must succeed", PdfiumDocument.STATUS_SUCCESS, status)

                    val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, null)
                    assertNotNull("Bitmap.wrapHardwareBuffer must return non-null Bitmap", bitmap)
                    assertEquals(Bitmap.Config.HARDWARE, bitmap!!.config)
                    assertEquals(512, bitmap.width)
                    assertEquals(512, bitmap.height)
                } finally {
                    hwBuffer.close()
                }
            }
        }
    }

    @Test
    fun testNativeRenderTileToDirectBuffer_rendersSuccessfully() {
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfiumEngine.openDocument(pfd).use { doc ->
                val buffer = ByteBuffer.allocateDirect(512 * 512 * 4)
                val status = doc.renderTileToDirectBuffer(
                    pageIndex = 0,
                    directBuffer = buffer,
                    fullWidth = 612,
                    fullHeight = 792,
                    startX = 0,
                    startY = 0,
                    tileWidth = 512,
                    tileHeight = 512
                )
                assertEquals("Native rendering to direct ByteBuffer must succeed", PdfiumDocument.STATUS_SUCCESS, status)

                // Background was cleared to opaque white (0xFFFFFFFF = all bytes 0xFF)
                buffer.rewind()
                val b0 = buffer.get().toInt() and 0xFF
                val b1 = buffer.get().toInt() and 0xFF
                val b2 = buffer.get().toInt() and 0xFF
                val b3 = buffer.get().toInt() and 0xFF
                assertEquals("Top-left pixel must be opaque white", 0xFF, b0)
                assertEquals("Top-left pixel must be opaque white", 0xFF, b1)
                assertEquals("Top-left pixel must be opaque white", 0xFF, b2)
                assertEquals("Top-left pixel must be opaque white", 0xFF, b3)
            }
        }
    }

    @Test
    fun testNativeRenderTile_oversizedTileRequestThrows() {
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfiumEngine.openDocument(pfd).use { doc ->
                val buffer = ByteBuffer.allocateDirect(1024)
                // Oversized tile width > MAX_TILE_DIMENSION (4096)
                assertThrows(IllegalArgumentException::class.java) {
                    doc.renderTileToDirectBuffer(
                        pageIndex = 0,
                        directBuffer = buffer,
                        fullWidth = 612,
                        fullHeight = 792,
                        startX = 0,
                        startY = 0,
                        tileWidth = 5000,
                        tileHeight = 512
                    )
                }

                // Oversized full dimensions > MAX_PAGE_DIMENSION (65536)
                assertThrows(IllegalArgumentException::class.java) {
                    doc.renderTileToDirectBuffer(
                        pageIndex = 0,
                        directBuffer = buffer,
                        fullWidth = 70000,
                        fullHeight = 70000,
                        startX = 0,
                        startY = 0,
                        tileWidth = 512,
                        tileHeight = 512
                    )
                }
            }
        }
    }

    @Test
    fun testNativeRenderTileToDirectBuffer_bufferTooSmallThrows() {
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfiumEngine.openDocument(pfd).use { doc ->
                // Buffer capacity is smaller than tileWidth * tileHeight * 4
                val buffer = ByteBuffer.allocateDirect(100)
                assertThrows(IllegalArgumentException::class.java) {
                    doc.renderTileToDirectBuffer(
                        pageIndex = 0,
                        directBuffer = buffer,
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
    }

    @Test
    fun testNativeRenderTileToDirectBuffer_withCancellationSignal_returnsCancelledStatus() {
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfiumEngine.openDocument(pfd).use { doc ->
                val buffer = ByteBuffer.allocateDirect(512 * 512 * 4)
                val cancelSignal = java.util.concurrent.atomic.AtomicBoolean(true)
                val status = doc.renderTileToDirectBuffer(
                    pageIndex = 0,
                    directBuffer = buffer,
                    fullWidth = 612,
                    fullHeight = 792,
                    startX = 0,
                    startY = 0,
                    tileWidth = 512,
                    tileHeight = 512,
                    cancellationSignal = cancelSignal
                )
                assertEquals(
                    "Native rendering with pre-cancelled signal must return STATUS_ERR_CANCELLED",
                    PdfiumDocument.STATUS_ERR_CANCELLED,
                    status
                )
            }
        }
    }

    @Test
    fun testNativeRenderTileToHardwareBuffer_withCancellationSignal_returnsCancelledStatus() {
        val file = copyAssetToTempFile("fixtures/valid_2pages.pdf")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfiumEngine.openDocument(pfd).use { doc ->
                val usage = HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                val hwBuffer = HardwareBuffer.create(512, 512, HardwareBuffer.RGBA_8888, 1, usage)
                try {
                    val cancelSignal = java.util.concurrent.atomic.AtomicBoolean(true)
                    val status = doc.renderTileToHardwareBuffer(
                        pageIndex = 0,
                        hardwareBuffer = hwBuffer,
                        fullWidth = 612,
                        fullHeight = 792,
                        startX = 0,
                        startY = 0,
                        tileWidth = 512,
                        tileHeight = 512,
                        cancellationSignal = cancelSignal
                    )
                    assertEquals(
                        "Native rendering to HardwareBuffer with cancelled signal must return STATUS_ERR_CANCELLED",
                        PdfiumDocument.STATUS_ERR_CANCELLED,
                        status
                    )
                } finally {
                    hwBuffer.close()
                }
            }
        }
    }

    private companion object {
        const val STRESS_ITERATIONS = 200
    }
}

