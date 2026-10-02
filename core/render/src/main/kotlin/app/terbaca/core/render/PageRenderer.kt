package app.terbaca.core.render

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import app.terbaca.core.model.PageInfo

/**
 * High-level engine-agnostic interface for opening documents, inspecting page geometry,
 * and dispatching render jobs.
 *
 * All implementations must guarantee safe execution off the UI thread per PRD §4.2.
 */
interface PageRenderer {

    /**
     * True if the underlying rendering backend (e.g. remote service process) is currently alive.
     */
    val isServiceConnected: Boolean

    /**
     * Opens a document from the provided [pfd], transferring ownership to the renderer.
     *
     * @return opaque positive document handle identifying the open document instance.
     */
    suspend fun openDocument(pfd: ParcelFileDescriptor, password: String? = null): Long

    /**
     * Returns total page count for the open document referenced by [docHandle].
     */
    suspend fun getPageCount(docHandle: Long): Int

    /**
     * Returns geometry metadata [PageInfo] for the specified [pageIndex].
     */
    suspend fun getPageInfo(docHandle: Long, pageIndex: Int): PageInfo

    /**
     * Returns geometry metadata [PageInfo] for all pages in the document.
     */
    suspend fun getAllPages(docHandle: Long): List<PageInfo>

    /**
     * Renders a page or sub-page tile into a GPU-ready [Bitmap] (config HARDWARE per PRD §4.3).
     *
     * @param docHandle The open document handle.
     * @param pageIndex 0-based page index.
     * @param fullWidth Full width of the page in device pixels at target zoom scale.
     * @param fullHeight Full height of the page in device pixels at target zoom scale.
     * @param startX Pixel X offset of the tile within the full-page raster.
     * @param startY Pixel Y offset of the tile within the full-page raster.
     * @param tileWidth Pixel width of the requested tile.
     * @param tileHeight Pixel height of the requested tile.
     * @return GPU-ready [Bitmap].
     */
    suspend fun renderTile(
        docHandle: Long,
        pageIndex: Int,
        fullWidth: Int,
        fullHeight: Int,
        startX: Int,
        startY: Int,
        tileWidth: Int,
        tileHeight: Int
    ): Bitmap

    /**
     * Closes the document handle and releases native memory and file descriptors.
     */
    suspend fun closeDocument(docHandle: Long)
}

