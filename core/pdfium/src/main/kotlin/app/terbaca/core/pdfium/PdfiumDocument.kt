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
        if (pageIndex < 0) {
            throw PdfPageNotFoundException("Invalid page index: $pageIndex (must be non-negative)")
        }
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

    /**
     * Extracts the metadata title (/Title) from the PDF Document Information Dictionary,
     * or null if absent or blank (F-LIB-1).
     */
    fun getTitle(): String? = synchronized(lock) {
        checkNotClosed()
        PdfiumNative.nativeGetDocumentTitle(nativePtr)
    }

    fun getPageText(pageIndex: Int): String? = synchronized(lock) {
        checkNotClosed()
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        PdfiumNative.nativeGetPageText(nativePtr, pageIndex)
    }

    fun getPageCharBoxes(pageIndex: Int): FloatArray? = synchronized(lock) {
        checkNotClosed()
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        PdfiumNative.nativeGetPageCharBoxes(nativePtr, pageIndex)
    }

    fun getPageLinks(pageIndex: Int): List<PageLinkParcel> = synchronized(lock) {
        checkNotClosed()
        if (pageIndex < 0 || pageIndex >= pageCount) return emptyList()
        val raw = PdfiumNative.nativeGetPageLinks(nativePtr, pageIndex) ?: return emptyList()
        raw.toList()
    }

    fun getOutline(): List<OutlineItemParcel> = synchronized(lock) {
        checkNotClosed()
        val raw = PdfiumNative.nativeGetOutline(nativePtr) ?: return emptyList()
        raw.toList()
    }

    fun getImageBounds(pageIndex: Int): FloatArray? = synchronized(lock) {
        checkNotClosed()
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        PdfiumNative.nativeGetImageBounds(nativePtr, pageIndex)
    }

    companion object {
        const val MAX_TILE_DIMENSION = 4096
        const val MAX_PAGE_DIMENSION = 65536

        const val STATUS_SUCCESS = 0
        const val STATUS_ERR_INVALID_PARAM = 1
        const val STATUS_ERR_BUFFER_TOO_SMALL = 2
        const val STATUS_ERR_UNSUPPORTED_FORMAT = 3
        const val STATUS_ERR_LOCK_FAILED = 4
        const val STATUS_ERR_PAGE_NOT_FOUND = 5
        const val STATUS_ERR_PDFIUM = 6
        const val STATUS_ERR_CANCELLED = 7
    }

    fun renderTileToHardwareBuffer(
        pageIndex: Int,
        hardwareBuffer: android.hardware.HardwareBuffer,
        fullWidth: Int,
        fullHeight: Int,
        startX: Int,
        startY: Int,
        tileWidth: Int,
        tileHeight: Int,
        cancellationSignal: AtomicBoolean? = null
    ): Int = synchronized(lock) {
        checkNotClosed()
        if (pageIndex < 0 || pageIndex >= pageCount) {
            throw PdfPageNotFoundException("Invalid page index: $pageIndex (document has $pageCount pages)")
        }
        require(tileWidth in 1..MAX_TILE_DIMENSION && tileHeight in 1..MAX_TILE_DIMENSION) {
            "Tile dimensions must be in 1..$MAX_TILE_DIMENSION ($tileWidth x $tileHeight)"
        }
        require(fullWidth in 1..MAX_PAGE_DIMENSION && fullHeight in 1..MAX_PAGE_DIMENSION) {
            "Full dimensions must be in 1..$MAX_PAGE_DIMENSION ($fullWidth x $fullHeight)"
        }
        PdfiumNative.nativeRenderTileToHardwareBuffer(
            docPtr = nativePtr,
            pageIndex = pageIndex,
            hardwareBufferObj = hardwareBuffer,
            fullWidth = fullWidth,
            fullHeight = fullHeight,
            startX = startX,
            startY = startY,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            cancellationSignal = cancellationSignal
        )
    }

    fun renderTileToDirectBuffer(
        pageIndex: Int,
        directBuffer: java.nio.ByteBuffer,
        fullWidth: Int,
        fullHeight: Int,
        startX: Int,
        startY: Int,
        tileWidth: Int,
        tileHeight: Int,
        stride: Int = tileWidth * 4,
        cancellationSignal: AtomicBoolean? = null
    ): Int = synchronized(lock) {
        checkNotClosed()
        if (pageIndex < 0 || pageIndex >= pageCount) {
            throw PdfPageNotFoundException("Invalid page index: $pageIndex (document has $pageCount pages)")
        }
        require(tileWidth in 1..MAX_TILE_DIMENSION && tileHeight in 1..MAX_TILE_DIMENSION) {
            "Tile dimensions must be in 1..$MAX_TILE_DIMENSION ($tileWidth x $tileHeight)"
        }
        require(fullWidth in 1..MAX_PAGE_DIMENSION && fullHeight in 1..MAX_PAGE_DIMENSION) {
            "Full dimensions must be in 1..$MAX_PAGE_DIMENSION ($fullWidth x $fullHeight)"
        }
        require(directBuffer.isDirect) { "directBuffer must be a direct ByteBuffer" }
        val requiredCapacity = stride.toLong() * tileHeight.toLong()
        require(directBuffer.capacity().toLong() >= requiredCapacity) {
            "directBuffer capacity (${directBuffer.capacity()}) is smaller than required ($requiredCapacity)"
        }
        PdfiumNative.nativeRenderTileToDirectBuffer(
            docPtr = nativePtr,
            pageIndex = pageIndex,
            directBuffer = directBuffer,
            fullWidth = fullWidth,
            fullHeight = fullHeight,
            startX = startX,
            startY = startY,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            stride = stride,
            cancellationSignal = cancellationSignal
        )
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
