package app.terbaca.feature.reader

import app.terbaca.core.model.PageLink
import app.terbaca.core.model.PageRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLinksTest {

    private val paragraph = PageLink(PageRect(50f, 100f, 550f, 200f), uri = "https://example.com")
    private val citation = PageLink(PageRect(300f, 140f, 320f, 152f), targetPageIndex = 9, targetY = 80f)

    @Test
    fun linkAt_prefersSmallestOverlappingLink() {
        assertEquals(citation, linkAt(listOf(paragraph, citation), 310f, 145f))
        assertEquals(paragraph, linkAt(listOf(paragraph, citation), 100f, 150f))
    }

    @Test
    fun linkAt_outsideAllLinks_isNull() {
        assertNull(linkAt(listOf(paragraph, citation), 10f, 10f))
    }

    @Test
    fun linkAt_ignoresLinksWithoutTarget() {
        val dead = PageLink(PageRect(0f, 0f, 100f, 100f))
        assertNull(linkAt(listOf(dead), 50f, 50f))
    }

    @Test
    fun isOpenableUri_allowsOnlyWebAndMail() {
        assertTrue(isOpenableUri("https://arxiv.org/abs/1706.03762"))
        assertTrue(isOpenableUri("HTTP://example.com"))
        assertTrue(isOpenableUri("mailto:author@example.com"))
        assertFalse(isOpenableUri("file:///sdcard/secret.pdf"))
        assertFalse(isOpenableUri("content://com.example/doc"))
        assertFalse(isOpenableUri("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isOpenableUri("javascript:alert(1)"))
        assertFalse(isOpenableUri("www.example.com"))
    }
}
