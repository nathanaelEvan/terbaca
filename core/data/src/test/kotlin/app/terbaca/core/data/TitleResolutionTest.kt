package app.terbaca.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleResolutionTest {

    @Test
    fun resolveTitle_withNonBlankMetadataTitle_returnsMetadataTitle() {
        val title = "Designing Data-Intensive Applications"
        val fallback = "ddia.pdf"
        assertEquals(title, "Designing Data-Intensive Applications".trim().takeIf { it.isNotEmpty() } ?: fallback)
    }

    @Test
    fun resolveTitle_withNullMetadataTitle_fallsBackToDisplayName() {
        val fallback = "document_123.pdf"
        val metadataTitle: String? = null
        val resolved = metadataTitle?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        assertEquals(fallback, resolved)
    }

    @Test
    fun resolveTitle_withBlankMetadataTitle_fallsBackToDisplayName() {
        val fallback = "document_123.pdf"
        val blankMetadataTitle = "   \n\t  "
        val resolved = blankMetadataTitle.trim().takeIf { it.isNotEmpty() } ?: fallback
        assertEquals(fallback, resolved)
    }
}
