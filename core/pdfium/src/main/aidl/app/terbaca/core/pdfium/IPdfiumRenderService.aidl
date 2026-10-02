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
     * Closes the document and releases native resources.
     */
    void closeDocument(long docHandle);

    /**
     * Returns total count of active open documents in the render process.
     */
    int getOpenDocumentCount();
}
