package app.terbaca.core.render

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InProcessPageRendererTest {

    @Test
    fun testOpenAndQueryPages() = runTest {
        val renderer = InProcessPageRenderer()
        assertTrue(renderer.isServiceConnected)

        val handle = renderer.createTestDocument(pageCount = 5, widthPoints = 600f, heightPoints = 800f)
        assertEquals(5, renderer.getPageCount(handle))

        val page0 = renderer.getPageInfo(handle, 0)
        assertEquals(0, page0.index)
        assertEquals(600f, page0.widthPoints)
        assertEquals(800f, page0.heightPoints)

        val allPages = renderer.getAllPages(handle)
        assertEquals(5, allPages.size)

        renderer.closeDocument(handle)
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { renderer.getPageCount(handle) }
        }
    }

    @Test
    fun testRenderTile() = runTest {
        var suppliedWidth = 0
        var suppliedHeight = 0
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val expectedBitmap = unsafe.allocateInstance(android.graphics.Bitmap::class.java) as android.graphics.Bitmap

        val renderer = InProcessPageRenderer(
            bitmapSupplier = { w, h ->
                suppliedWidth = w
                suppliedHeight = h
                expectedBitmap
            }
        )
        val handle = renderer.createTestDocument(pageCount = 2)
        val bitmap = renderer.renderTile(
            docHandle = handle,
            pageIndex = 0,
            fullWidth = 1000,
            fullHeight = 1000,
            startX = 0,
            startY = 0,
            tileWidth = 512,
            tileHeight = 512
        )
        assertEquals(expectedBitmap, bitmap)
        assertEquals(512, suppliedWidth)
        assertEquals(512, suppliedHeight)
    }

    @Test
    fun testRecoveryListeners() {
        val renderer = InProcessPageRenderer()
        var invocationCount = 0
        val unregister = renderer.registerRecoveryListener {
            invocationCount++
        }

        renderer.simulateRecovery()
        assertEquals(1, invocationCount)

        renderer.simulateRecovery()
        assertEquals(2, invocationCount)

        unregister()
        renderer.simulateRecovery()
        assertEquals(2, invocationCount)
    }
}

