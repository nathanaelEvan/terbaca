package app.terbaca.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageModelTest {

    @Test
    fun pageRect_propertiesAndContains() {
        val rect = PageRect(left = 10f, top = 20f, right = 110f, bottom = 120f)
        assertEquals(100f, rect.width, 0.001f)
        assertEquals(100f, rect.height, 0.001f)
        assertEquals(60f, rect.centerX, 0.001f)
        assertEquals(70f, rect.centerY, 0.001f)

        assertTrue(rect.contains(10f, 20f))
        assertTrue(rect.contains(60f, 70f))
        assertTrue(rect.contains(110f, 120f))
        assertFalse(rect.contains(9.9f, 50f))
        assertFalse(rect.contains(50f, 19.9f))
        assertFalse(rect.contains(110.1f, 50f))
        assertFalse(rect.contains(50f, 120.1f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pageRect_invalidHorizontalBounds_throws() {
        PageRect(left = 100f, top = 10f, right = 50f, bottom = 20f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pageRect_invalidVerticalBounds_throws() {
        PageRect(left = 10f, top = 100f, right = 50f, bottom = 20f)
    }

    @Test
    fun pageLink_internalAndExternal() {
        val rect = PageRect(50f, 100f, 150f, 120f)
        val internalLink = PageLink(
            sourceRect = rect,
            targetPageIndex = 3,
            targetX = 72f,
            targetY = 144f
        )
        assertTrue(internalLink.isInternal)
        assertFalse(internalLink.isExternal)
        assertEquals(3, internalLink.targetPageIndex)
        assertEquals(72f, internalLink.targetX ?: 0f, 0.001f)
        assertEquals(144f, internalLink.targetY ?: 0f, 0.001f)

        val externalLink = PageLink(
            sourceRect = rect,
            uri = "https://example.com/paper"
        )
        assertFalse(externalLink.isInternal)
        assertTrue(externalLink.isExternal)
        assertEquals(-1, externalLink.targetPageIndex)
        assertEquals("https://example.com/paper", externalLink.uri)
    }

    @Test
    fun outlineItem_hierarchy() {
        val sub1 = OutlineItem(title = "1.1 Subsection", pageIndex = 1)
        val sub2 = OutlineItem(title = "1.2 Subsection", pageIndex = 2)
        val root = OutlineItem(
            title = "1. Introduction",
            pageIndex = 0,
            children = listOf(sub1, sub2)
        )

        assertEquals("1. Introduction", root.title)
        assertEquals(0, root.pageIndex)
        assertEquals(2, root.children.size)
        assertEquals("1.1 Subsection", root.children[0].title)
        assertEquals(1, root.children[0].pageIndex)
    }

    @Test
    fun pageText_creationAndBoxes() {
        val box1 = CharBox(10f, 20f, 15f, 30f)
        val box2 = CharBox(15f, 20f, 20f, 30f)
        val pageText = PageText(
            pageIndex = 0,
            text = "Hi",
            charBoxes = listOf(box1, box2)
        )

        assertEquals(0, pageText.pageIndex)
        assertEquals("Hi", pageText.text)
        assertEquals(2, pageText.charBoxes.size)
    }
}
