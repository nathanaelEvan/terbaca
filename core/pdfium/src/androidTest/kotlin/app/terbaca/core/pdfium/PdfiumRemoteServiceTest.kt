package app.terbaca.core.pdfium

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class PdfiumRemoteServiceTest {

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
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

            val handle = renderer.openDocument(pfd)
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
        val pfdNoPass = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        assertThrows(PdfPasswordException::class.java) {
            runBlocking {
                renderer.openDocument(pfdNoPass)
            }
        }

        // With correct password
        val pfdPass = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        runBlocking {
            val handle = renderer.openDocument(pfdPass, password = "secret123")
            assertTrue(handle > 0L)
            assertEquals(1, renderer.getPageCount(handle))
            renderer.closeDocument(handle)
        }
    }

    @Test
    fun testRemoteRenderService_corruptDocument() {
        val file = copyAssetToTempFile("fixtures/corrupt.pdf")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        assertThrows(PdfCorruptException::class.java) {
            runBlocking {
                renderer.openDocument(pfd)
            }
        }
    }
}
