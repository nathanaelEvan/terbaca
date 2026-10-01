package app.terbaca.core.pdfium

import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native engine interface for PDFium initialization and document lifecycle.
 * Ensures thread-safe access and positional pread I/O over file descriptors.
 */
class PdfiumEngine {

    private val isInitialized = AtomicBoolean(false)

    fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            PdfiumNative.ensureLoaded()
            PdfiumNative.nativeInitLibrary()
        }
    }

    fun destroy() {
        if (isInitialized.compareAndSet(true, false)) {
            PdfiumNative.nativeDestroyLibrary()
        }
    }

    fun openDocument(fd: Int, password: String? = null): PdfiumDocument {
        initialize()
        require(fd >= 0) { "File descriptor must be non-negative: $fd" }
        val passwordBytes = password?.let {
            it.toByteArray(Charsets.UTF_8) + 0.toByte()
        }
        val outError = IntArray(1)
        val docPtr = PdfiumNative.nativeOpenDocument(fd, passwordBytes, outError)
        if (docPtr == 0L) {
            val errCode = outError[0]
            throw PdfiumException.fromErrorCode(errCode, "Failed to open PDF from file descriptor $fd")
        }
        return PdfiumDocument(docPtr)
    }

    fun openDocument(pfd: ParcelFileDescriptor, password: String? = null): PdfiumDocument {
        return openDocument(pfd.fd, password)
    }
}
