package app.terbaca.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleResolutionTest {

    @Test
    fun resolveTitle_withNonBlankMetadataTitle_returnsMetadataTitle() {
        val title = "Designing Data-Intensive Applications"
        val fallback = "ddia.pdf"
        assertEquals(title, resolveTitle(title, fallback))
    }

    @Test
    fun resolveTitle_withWhitespacePaddedMetadataTitle_returnsTrimmedMetadataTitle() {
        val title = "   Designing Data-Intensive Applications   \n"
        val fallback = "ddia.pdf"
        assertEquals("Designing Data-Intensive Applications", resolveTitle(title, fallback))
    }

    @Test
    fun resolveTitle_withNullMetadataTitle_fallsBackToDisplayName() {
        val fallback = "document_123.pdf"
        val metadataTitle: String? = null
        assertEquals(fallback, resolveTitle(metadataTitle, fallback))
    }

    @Test
    fun resolveTitle_withBlankMetadataTitle_fallsBackToDisplayName() {
        val fallback = "document_123.pdf"
        val blankMetadataTitle = "   \n\t  "
        assertEquals(fallback, resolveTitle(blankMetadataTitle, fallback))
    }
}
