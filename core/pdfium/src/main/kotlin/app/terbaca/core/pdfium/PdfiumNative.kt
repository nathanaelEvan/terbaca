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
    external fun nativeRenderTileToHardwareBuffer(
        docPtr: Long,
        pageIndex: Int,
        hardwareBufferObj: android.hardware.HardwareBuffer,
        fullWidth: Int,
        fullHeight: Int,
        startX: Int,
        startY: Int,
        tileWidth: Int,
        tileHeight: Int,
        cancellationSignal: Any?
    ): Int
    external fun nativeRenderTileToDirectBuffer(
        docPtr: Long,
        pageIndex: Int,
        directBuffer: java.nio.ByteBuffer,
        fullWidth: Int,
        fullHeight: Int,
        startX: Int,
        startY: Int,
        tileWidth: Int,
        tileHeight: Int,
        stride: Int,
        cancellationSignal: Any?
    ): Int
    external fun nativeCloseDocument(docPtr: Long)
    external fun nativeGetLastError(): Int
}

