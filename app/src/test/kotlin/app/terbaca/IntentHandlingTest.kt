package app.terbaca

import android.content.Intent
import app.terbaca.core.pdfium.PdfCorruptException
import app.terbaca.core.pdfium.PdfFileException
import app.terbaca.core.pdfium.PdfPasswordException
import app.terbaca.core.pdfium.PdfSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class IntentHandlingTest {

    @Test
    fun extractPdfUri_nullIntent_returnsNull() {
        assertNull(extractPdfUri(null))
    }

    @Test
    fun toDocumentError_passwordException_mapsToEncrypted() {
        val err = PdfPasswordException("Password required").toDocumentError()
        assertTrue(err is DocumentError.Encrypted)
        assertEquals(R.string.error_encrypted_title, err.titleRes)
        assertEquals(R.string.error_encrypted_message, err.messageRes)
    }

    @Test
    fun toDocumentError_securityException_mapsToEncrypted() {
        val err = PdfSecurityException("Unsupported encryption").toDocumentError()
        assertTrue(err is DocumentError.Encrypted)
    }

    @Test
    fun toDocumentError_corruptException_mapsToCorrupt() {
        val err = PdfCorruptException("Corrupt PDF syntax").toDocumentError()
        assertTrue(err is DocumentError.Corrupt)
        assertEquals(R.string.error_corrupt_title, err.titleRes)
        assertEquals(R.string.error_corrupt_message, err.messageRes)
    }

    @Test
    fun toDocumentError_fileNotFound_mapsToUnavailable() {
        val err = FileNotFoundException("File missing").toDocumentError()
        assertTrue(err is DocumentError.Unavailable)
        assertEquals(R.string.error_unavailable_title, err.titleRes)
        assertEquals(R.string.error_unavailable_message, err.messageRes)
    }

    @Test
    fun toDocumentError_securityException_mapsToUnavailable() {
        val err = SecurityException("Permission denied").toDocumentError()
        assertTrue(err is DocumentError.Unavailable)
    }

    @Test
    fun toDocumentError_pdfFileException_mapsToUnavailable() {
        val err = PdfFileException("Could not open file").toDocumentError()
        assertTrue(err is DocumentError.Unavailable)
    }

    @Test
    fun toDocumentError_ioException_mapsToUnavailable() {
        val err = IOException("Disk read error").toDocumentError()
        assertTrue(err is DocumentError.Unavailable)
    }

    @Test
    fun toDocumentError_unknownException_mapsToGeneric() {
        val err = IllegalStateException("Something broke").toDocumentError()
        assertTrue(err is DocumentError.Generic)
        assertEquals(R.string.error_generic_title, err.titleRes)
        assertEquals(R.string.error_generic_message, err.messageRes)
    }

    @Test
    fun documentError_keys_and_fromKey_roundTrip() {
        val encrypted = DocumentError.Encrypted()
        assertEquals(DocumentError.KEY_ENCRYPTED, encrypted.key)
        assertTrue(DocumentError.fromKey(encrypted.key) is DocumentError.Encrypted)

        val corrupt = DocumentError.Corrupt()
        assertEquals(DocumentError.KEY_CORRUPT, corrupt.key)
        assertTrue(DocumentError.fromKey(corrupt.key) is DocumentError.Corrupt)

        val unavailable = DocumentError.Unavailable()
        assertEquals(DocumentError.KEY_UNAVAILABLE, unavailable.key)
        assertTrue(DocumentError.fromKey(unavailable.key) is DocumentError.Unavailable)

        val generic = DocumentError.Generic()
        assertEquals(DocumentError.KEY_GENERIC, generic.key)
        assertTrue(DocumentError.fromKey(generic.key) is DocumentError.Generic)

        assertNull(DocumentError.fromKey("unknown"))
        assertNull(DocumentError.fromKey(null))
    }

    @Test
    fun isAllowedPdfScheme_contentScheme_returnsTrue() {
        assertTrue(isAllowedPdfScheme("content"))
        assertTrue(isAllowedPdfScheme("CONTENT"))
    }

    @Test
    fun isAllowedPdfScheme_nonContentScheme_returnsFalse() {
        assertFalse(isAllowedPdfScheme("file"))
        assertFalse(isAllowedPdfScheme("http"))
        assertFalse(isAllowedPdfScheme("https"))
        assertFalse(isAllowedPdfScheme(""))
        assertFalse(isAllowedPdfScheme(null))
    }
}
