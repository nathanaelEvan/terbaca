package app.terbaca.core.pdfium;

import android.os.ParcelFileDescriptor;

/**
 * Binder interface for the dedicated out-of-process PDFium render service.
 * All PDFium operations run on a single dedicated render thread inside
 * the :render service process per PRD §4.2.
 */
interface IPdfiumRenderService {

    /**
     * Opens a PDF document from the given file descriptor.
     * The service takes ownership and duplicates/holds the descriptor.
     *
     * @return opaque positive document handle, or throws an exception on failure.
     */
    long openDocument(in ParcelFileDescriptor pfd, String password);

    /**
     * Returns total page count for the open document.
     */
    int getPageCount(long docHandle);

    /**
     * Returns page dimensions in PDF points as a 2-element array [widthPoints, heightPoints].
     */
    float[] getPageSize(long docHandle, int pageIndex);

    /**
     * Closes the document and releases native resources.
     */
    void closeDocument(long docHandle);

    /**
     * Returns total count of active open documents in the render process.
     */
    int getOpenDocumentCount();
}
