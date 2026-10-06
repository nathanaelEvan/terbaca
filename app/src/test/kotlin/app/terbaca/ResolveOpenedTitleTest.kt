package app.terbaca

import app.terbaca.core.render.InProcessPageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ResolveOpenedTitleTest {

    @Test
    fun resolveOpenedTitle_whenLookupThrows_fallsBackToFilename() = runTest {
        val renderer = object : InProcessPageRenderer() {
            override suspend fun getDocumentTitle(docHandle: Long): String? =
                throw IllegalStateException("Binder transaction or PDFium error")
        }
        val resolved = resolveOpenedTitle(renderer, docHandle = 42L, fallbackDisplayName = "sample.pdf")
        assertEquals("sample.pdf", resolved)
    }

    @Test
    fun resolveOpenedTitle_whenLookupThrowsCancellationException_rethrows() = runTest {
        val renderer = object : InProcessPageRenderer() {
            override suspend fun getDocumentTitle(docHandle: Long): String? =
                throw CancellationException("Operation cancelled")
        }
        try {
            resolveOpenedTitle(renderer, docHandle = 42L, fallbackDisplayName = "sample.pdf")
            fail("Expected CancellationException to be rethrown")
        } catch (_: CancellationException) {
            // Success: CancellationException must not be swallowed
        }
    }

    @Test
    fun resolveOpenedTitle_whenLookupReturnsTitle_usesMetadataTitle() = runTest {
        val renderer = InProcessPageRenderer()
        val handle = renderer.createTestDocument(title = "Designing Data-Intensive Applications")
        val resolved = resolveOpenedTitle(renderer, docHandle = handle, fallbackDisplayName = "ddia.pdf")
        assertEquals("Designing Data-Intensive Applications", resolved)
    }

    @Test
    fun resolveOpenedTitle_whenLookupReturnsBlank_fallsBackToFilename() = runTest {
        val renderer = InProcessPageRenderer()
        val handle = renderer.createTestDocument(title = "   \t\n  ")
        val resolved = resolveOpenedTitle(renderer, docHandle = handle, fallbackDisplayName = "sample.pdf")
        assertEquals("sample.pdf", resolved)
    }
}
