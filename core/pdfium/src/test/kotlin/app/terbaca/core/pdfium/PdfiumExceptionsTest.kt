package app.terbaca.core.pdfium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfiumExceptionsTest {

    @Test
    fun testErrorCodeMapping() {
        val passwordEx = PdfiumException.fromErrorCode(PdfiumException.ERROR_PASSWORD)
        assertTrue(passwordEx is PdfPasswordException)
        assertEquals(PdfiumException.ERROR_PASSWORD, passwordEx.errorCode)

        val corruptEx = PdfiumException.fromErrorCode(PdfiumException.ERROR_FORMAT)
        assertTrue(corruptEx is PdfCorruptException)
        assertEquals(PdfiumException.ERROR_FORMAT, corruptEx.errorCode)

        val fileEx = PdfiumException.fromErrorCode(PdfiumException.ERROR_FILE)
        assertTrue(fileEx is PdfFileException)
        assertEquals(PdfiumException.ERROR_FILE, fileEx.errorCode)

        val securityEx = PdfiumException.fromErrorCode(PdfiumException.ERROR_SECURITY)
        assertTrue(securityEx is PdfSecurityException)
        assertEquals(PdfiumException.ERROR_SECURITY, securityEx.errorCode)

        val pageEx = PdfiumException.fromErrorCode(PdfiumException.ERROR_PAGE)
        assertTrue(pageEx is PdfPageNotFoundException)
        assertEquals(PdfiumException.ERROR_PAGE, pageEx.errorCode)

        val unknownEx = PdfiumException.fromErrorCode(999)
        assertEquals(999, unknownEx.errorCode)
    }

    @Test
    fun testCustomMessagePreserved() {
        val customMsg = "Custom file error"
        val ex = PdfiumException.fromErrorCode(PdfiumException.ERROR_FILE, customMsg)
        assertEquals(customMsg, ex.message)
    }
}
