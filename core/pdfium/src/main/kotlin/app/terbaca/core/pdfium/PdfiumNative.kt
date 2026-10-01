package app.terbaca.core.pdfium

internal object PdfiumNative {
    private var nativeLoaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!nativeLoaded) {
            System.loadLibrary("pdfium")
            System.loadLibrary("terbaca_pdfium")
            nativeLoaded = true
        }
    }

    external fun nativeInitLibrary()
    external fun nativeDestroyLibrary()
    external fun nativeOpenDocument(fd: Int, password: ByteArray?, outError: IntArray): Long
    external fun nativeGetPageCount(docPtr: Long): Int
    external fun nativeGetPageSize(docPtr: Long, pageIndex: Int, outSize: FloatArray): Boolean
    external fun nativeCloseDocument(docPtr: Long)
    external fun nativeGetLastError(): Int
}
