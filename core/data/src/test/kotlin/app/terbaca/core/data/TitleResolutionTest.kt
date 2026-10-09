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

    @Test
    fun resolveTitle_withToolDefaultTitles_fallsBackToDisplayName() {
        val fallback = "thesis-final.pdf"
        listOf(
            "Microsoft Word - Document1.docx",
            "Microsoft PowerPoint - pitch_v3.pptx",
            "Untitled",
            "untitled-1",
            "PowerPoint Presentation",
            "Presentation1",
            "Document1",
            "Slide 1",
            "main.tex",
            "paper.dvi",
            "Report.pdf",
            "(anonymous)",
        ).forEach { junk -> assertEquals(junk, fallback, resolveTitle(junk, fallback)) }
    }

    @Test
    fun resolveTitle_withMeaningfulTitles_keepsThem() {
        listOf(
            "Attention Is All You Need",
            "Presentation Skills for Engineers",
            "Document Layout Analysis with Transformers",
            "Untangling Distributed Systems",
        ).forEach { title -> assertEquals(title, resolveTitle(title, "x.pdf")) }
    }
}
