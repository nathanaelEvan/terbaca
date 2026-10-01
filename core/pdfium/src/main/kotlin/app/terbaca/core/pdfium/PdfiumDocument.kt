package app.terbaca.core.pdfium

import app.terbaca.core.model.PageInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents an open PDF document loaded in the native PDFium engine.
 *
 * Concurrency requirement (PRD §4.2):
 * All operations on a [PdfiumDocument], including [close], must be confined
 * to the dedicated render thread inside the :render service process.
 */
class PdfiumDocument internal constructor(
    @Volatile private var nativePtr: Long,
    private val onClose: ((PdfiumDocument) -> Unit)? = null
) : AutoCloseable {

    private val isClosed = AtomicBoolean(false)
    private val lock = Any()

    val pageCount: Int
        get() = synchronized(lock) {
            checkNotClosed()
            PdfiumNative.nativeGetPageCount(nativePtr)
        }

    fun getPageSize(pageIndex: Int): PageInfo = synchronized(lock) {
        checkNotClosed()
        require(pageIndex >= 0) { "pageIndex must be non-negative: $pageIndex" }
        val sizeBuf = FloatArray(2)
        val success = PdfiumNative.nativeGetPageSize(nativePtr, pageIndex, sizeBuf)
        if (!success) {
            throw PdfPageNotFoundException("Failed to get size for page $pageIndex")
        }
        PageInfo(
            index = pageIndex,
            widthPoints = sizeBuf[0],
            heightPoints = sizeBuf[1]
        )
    }

    fun getAllPages(): List<PageInfo> = synchronized(lock) {
        checkNotClosed()
        val count = pageCount
        (0 until count).map { index -> getPageSize(index) }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            val ptr = synchronized(lock) {
                val p = nativePtr
                nativePtr = 0L
                p
            }
            if (ptr != 0L) {
                PdfiumNative.nativeCloseDocument(ptr)
            }
            onClose?.invoke(this)
        }
    }

    private fun checkNotClosed() {
        check(!isClosed.get() && nativePtr != 0L) { "PdfiumDocument is already closed" }
    }
}
