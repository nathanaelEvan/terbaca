package app.terbaca.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageInfoTest {

    @Test
    fun `portrait page reports correct aspect ratio and landscape state`() {
        val page = PageInfo(index = 0, widthPoints = 612f, heightPoints = 792f)
        assertFalse(page.isLandscape)
        assertEquals(612f / 792f, page.aspectRatio, 0.001f)
    }

    @Test
    fun `landscape page reports isLandscape true`() {
        val page = PageInfo(index = 1, widthPoints = 1920f, heightPoints = 1080f)
        assertTrue(page.isLandscape)
        assertEquals(1920f / 1080f, page.aspectRatio, 0.001f)
    }
}
