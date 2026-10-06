package app.terbaca.core.pdfium;

import android.os.ParcelFileDescriptor;
import app.terbaca.core.pdfium.RenderResult;

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
}

