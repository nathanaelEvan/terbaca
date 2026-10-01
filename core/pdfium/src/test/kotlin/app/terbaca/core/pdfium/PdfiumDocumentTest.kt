package app.terbaca.core.pdfium

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class PdfiumDocumentTest {

    @Test
    fun testClosedDocumentThrowsIllegalStateException() {
        var closedCallbackFired = false
        val doc = PdfiumDocument(0L) {
            closedCallbackFired = true
        }

        // Close it immediately
        doc.close()
        assertTrue(closedCallbackFired)

        // Multiple closes should be idempotent and not fail
        doc.close()

        assertThrows(IllegalStateException::class.java) {
            doc.pageCount
        }
        assertThrows(IllegalStateException::class.java) {
            doc.getPageSize(0)
        }
        assertThrows(IllegalStateException::class.java) {
            doc.getAllPages()
        }
    }
}
