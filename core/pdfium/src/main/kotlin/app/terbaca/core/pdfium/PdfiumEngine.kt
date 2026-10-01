package app.terbaca.core.pdfium

import android.os.ParcelFileDescriptor
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton native engine interface for PDFium initialization and document lifecycle.
 *
 * Concurrency & Architecture (PRD §4.2):
 * PDFium maintains process-global state and is strictly non-thread-safe.
 * [PdfiumEngine] is a singleton object that manages the library lifecycle,
 * tracks open documents, and prevents library destruction while documents are active.
 */
object PdfiumEngine {

    private val isInitialized = AtomicBoolean(false)
    private val openDocuments: MutableSet<PdfiumDocument> =
        Collections.newSetFromMap(ConcurrentHashMap<PdfiumDocument, Boolean>())

    @Synchronized
    fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            PdfiumNative.ensureLoaded()
            PdfiumNative.nativeInitLibrary()
        }
    }

    @Synchronized
    fun destroy() {
        check(openDocuments.isEmpty()) {
            "Cannot destroy PdfiumEngine while ${openDocuments.size} document(s) are still open"
        }
        if (isInitialized.compareAndSet(true, false)) {
            PdfiumNative.nativeDestroyLibrary()
        }
    }

    /**
     * Synchronized with [initialize] and [destroy] so the library cannot be torn down
     * between the native open and registration in [openDocuments].
     */
    @Synchronized
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
        val doc = PdfiumDocument(docPtr) { closedDoc ->
            openDocuments.remove(closedDoc)
        }
        openDocuments.add(doc)
        return doc
    }

    fun openDocument(pfd: ParcelFileDescriptor, password: String? = null): PdfiumDocument {
        return openDocument(pfd.fd, password)
    }

    val openDocumentCount: Int
        get() = openDocuments.size
}
