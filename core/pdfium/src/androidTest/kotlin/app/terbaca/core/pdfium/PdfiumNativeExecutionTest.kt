package app.terbaca.core.pdfium

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

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
}
