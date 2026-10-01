package app.terbaca.core.pdfium

import app.terbaca.core.model.PageInfo
import java.util.concurrent.atomic.AtomicBoolean

class PdfiumDocument internal constructor(
    private var nativePtr: Long
) : AutoCloseable {

    private val isClosed = AtomicBoolean(false)

    val pageCount: Int
        get() {
            checkNotClosed()
            return PdfiumNative.nativeGetPageCount(nativePtr)
        }

    fun getPageSize(pageIndex: Int): PageInfo {
        checkNotClosed()
        require(pageIndex >= 0) { "pageIndex must be non-negative: $pageIndex" }
        val sizeBuf = FloatArray(2)
        val success = PdfiumNative.nativeGetPageSize(nativePtr, pageIndex, sizeBuf)
        if (!success) {
            throw PdfPageNotFoundException("Failed to get size for page $pageIndex")
        }
        return PageInfo(
            index = pageIndex,
            widthPoints = sizeBuf[0],
            heightPoints = sizeBuf[1]
        )
    }

    fun getAllPages(): List<PageInfo> {
        checkNotClosed()
        val count = pageCount
        return (0 until count).map { index -> getPageSize(index) }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            val ptr = nativePtr
            nativePtr = 0L
            if (ptr != 0L) {
                PdfiumNative.nativeCloseDocument(ptr)
            }
        }
    }

    private fun checkNotClosed() {
        check(!isClosed.get()) { "PdfiumDocument is already closed" }
    }
}
