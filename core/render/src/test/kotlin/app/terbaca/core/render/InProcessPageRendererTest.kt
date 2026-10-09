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

    @Test
    fun testExtractionApis() = runTest {
        val renderer = InProcessPageRenderer()
        val handle = renderer.createTestDocument(pageCount = 3)

        val outline = listOf(
            app.terbaca.core.model.OutlineItem("Introduction", 0),
            app.terbaca.core.model.OutlineItem("Methods", 1)
        )
        renderer.setOutline(handle, outline)
        assertEquals(outline, renderer.getOutline(handle))

        val text = app.terbaca.core.model.PageText(
            pageIndex = 0,
            text = "Hello World",
            charBoxes = listOf(app.terbaca.core.model.CharBox(10f, 10f, 20f, 20f))
        )
        renderer.setPageText(handle, 0, text)
        assertEquals(text, renderer.getPageText(handle, 0))

        val links = listOf(
            app.terbaca.core.model.PageLink(
                sourceRect = app.terbaca.core.model.PageRect(50f, 50f, 100f, 70f),
                targetPageIndex = 1
            )
        )
        renderer.setPageLinks(handle, 0, links)
        assertEquals(links, renderer.getPageLinks(handle, 0))

        val images = listOf(
            app.terbaca.core.model.PageRect(100f, 100f, 300f, 250f)
        )
        renderer.setImageBounds(handle, 0, images)
        assertEquals(images, renderer.getImageBounds(handle, 0))

        renderer.closeDocument(handle)
        assertTrue(renderer.getOutline(handle).isEmpty())
        assertTrue(renderer.getPageLinks(handle, 0).isEmpty())
        assertTrue(renderer.getImageBounds(handle, 0).isEmpty())
    }
}

