package app.terbaca.core.render

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import app.terbaca.core.model.PageInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process mock implementation of [PageRenderer] for unit testing and M1 prototyping per PRD §4.2.
 */
class InProcessPageRenderer(
    private val defaultPageCount: Int = 1,
    private val defaultWidthPoints: Float = 612f,
    private val defaultHeightPoints: Float = 792f,
    private val bitmapSupplier: (width: Int, height: Int) -> Bitmap = { w, h ->
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }
) : PageRenderer {


    private val documents = ConcurrentHashMap<Long, List<PageInfo>>()
    private val nextHandle = AtomicLong(1L)
    override var isServiceConnected: Boolean = true

    fun createTestDocument(
        pageCount: Int = defaultPageCount,
        widthPoints: Float = defaultWidthPoints,
        heightPoints: Float = defaultHeightPoints
    ): Long {
        check(isServiceConnected) { "Service is disconnected" }
        val handle = nextHandle.getAndIncrement()
        val pages = (0 until pageCount).map { i ->
            PageInfo(index = i, widthPoints = widthPoints, heightPoints = heightPoints)
        }
        documents[handle] = pages
        return handle
    }

    override suspend fun openDocument(pfd: ParcelFileDescriptor, password: String?): Long {
        check(isServiceConnected) { "Service is disconnected" }
        runCatching { pfd.close() }
        return createTestDocument()
    }

    override suspend fun getPageCount(docHandle: Long): Int {
        check(isServiceConnected) { "Service is disconnected" }
        val pages = documents[docHandle] ?: error("Invalid document handle: $docHandle")
        return pages.size
    }

    override suspend fun getPageInfo(docHandle: Long, pageIndex: Int): PageInfo {
        check(isServiceConnected) { "Service is disconnected" }
        val pages = documents[docHandle] ?: error("Invalid document handle: $docHandle")
        return pages.getOrNull(pageIndex) ?: error("Page index out of bounds: $pageIndex")
    }

    override suspend fun getAllPages(docHandle: Long): List<PageInfo> {
        check(isServiceConnected) { "Service is disconnected" }
        return documents[docHandle] ?: error("Invalid document handle: $docHandle")
    }

    override suspend fun renderTile(
        docHandle: Long,
        pageIndex: Int,
        fullWidth: Int,
        fullHeight: Int,
        startX: Int,
        startY: Int,
        tileWidth: Int,
        tileHeight: Int
    ): Bitmap {
        check(isServiceConnected) { "Service is disconnected" }
        getPageInfo(docHandle, pageIndex)
        return bitmapSupplier(tileWidth, tileHeight)
    }

    override suspend fun closeDocument(docHandle: Long) {
        documents.remove(docHandle)
    }
}

