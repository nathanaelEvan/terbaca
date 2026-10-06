package app.terbaca.core.pdfium;

import android.os.ParcelFileDescriptor;
import app.terbaca.core.pdfium.RenderResult;
import app.terbaca.core.pdfium.PageLinkParcel;
import app.terbaca.core.pdfium.OutlineItemParcel;

/**
 * Binder interface for the dedicated out-of-process PDFium render service.
 * All PDFium operations run on a single dedicated render thread inside
 * the :render service process per PRD §4.2.
 */
interface IPdfiumRenderService {

    /**
     * Opens a PDF document from the given file descriptor.
     * Returns a RenderResult containing the positive document handle on success,
     * or an error code on failure.
     */
    RenderResult openDocument(in ParcelFileDescriptor pfd, String password);

    /**
     * Returns a RenderResult containing total page count for the open document.
     */
    RenderResult getPageCount(long docHandle);

    /**
     * Returns a RenderResult containing page dimensions in PDF points [width, height].
     */
    RenderResult getPageSize(long docHandle, int pageIndex);

    /**
     * Renders a page or sub-page tile into a HardwareBuffer (or SharedMemory fallback).
     * The in-flight render can be cancelled via cancelTile(renderId).
     */
    RenderResult renderTile(
        long renderId,
        long docHandle,
        int pageIndex,
        int fullWidth,
        int fullHeight,
        int startX,
        int startY,
        int tileWidth,
        int tileHeight,
        boolean forceFallback
    );

    /**
     * Cancels an in-flight or pending tile render identified by renderId.
     */
    oneway void cancelTile(long renderId);

    /**
     * Cancels all in-flight or pending tile renders for docHandle.
     */
    oneway void cancelAllTiles(long docHandle);

    /**
     * Closes the document and releases native resources.
     */
    void closeDocument(long docHandle);

    /**
     * Returns total count of active open documents in the render process.
     */
    int getOpenDocumentCount();

    /**
     * Returns the metadata title of the document, or null if none is specified (F-LIB-1).
     */
    String getDocumentTitle(long docHandle);

    /**
     * Extracts full unicode text for the page specified by pageIndex (M3-01).
     */
    String getPageText(long docHandle, int pageIndex);

    /**
     * Extracts character bounding boxes for the page in top-left coordinates (M3-01).
     * Packed as 4 floats per character: [left, top, right, bottom].
     */
    float[] getPageCharBoxes(long docHandle, int pageIndex);

    /**
     * Extracts hyperlinks present on the page (M3-01).
     */
    List<PageLinkParcel> getPageLinks(long docHandle, int pageIndex);

    /**
     * Extracts the hierarchical document outline (bookmarks / TOC) (M3-01).
     */
    List<OutlineItemParcel> getOutline(long docHandle);

    /**
     * Extracts bounding boxes of raster image objects on the page (M3-01).
     * Packed as 4 floats per image: [left, top, right, bottom].
     */
    float[] getImageBounds(long docHandle, int pageIndex);
}
