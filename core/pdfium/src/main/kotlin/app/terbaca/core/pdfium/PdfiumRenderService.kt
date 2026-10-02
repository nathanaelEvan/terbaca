package app.terbaca.core.pdfium

import android.app.Service
import android.content.Intent
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong


/**
 * Android Service hosting PDFium native operations inside a dedicated ":render" process.
 * All calls are strictly serialized onto a single render thread per PRD §4.2.
 */
class PdfiumRenderService : Service() {

    companion object {
        private const val TAG = "PdfiumRenderService"
    }

    private val activeDocuments = ConcurrentHashMap<Long, PdfiumDocument>()

    // Sticky flag tracking hardware transport failure on this device (PRD §4.3 review item)
    @Volatile private var isHardwareTransportBroken = false

    // High 32 bits contain a random positive epoch unique to this process lifetime.
    // Mask with 0x7FFF_FFFF to ensure positive signed Long.
    private val processEpoch: Long =
        (ThreadLocalRandom.current().nextLong(1L, 0x7FFF_FFFFL) shl 32)
    private val sequence = AtomicLong(1L)

    // Per-thread cleanup for parcelable native buffers transferred across Binder
    private val pendingBufferCleanup = ThreadLocal<HardwareBuffer?>()
    private val pendingSharedMemCleanup = ThreadLocal<SharedMemory?>()

    // Dedicated single render thread as required by PRD §4.2
    private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PdfiumRenderThread").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }

    private fun allocateHandle(): Long {
        val seq = sequence.getAndIncrement() and 0xFFFF_FFFFL
        return processEpoch or seq
    }

    private fun getDocumentOrThrow(docHandle: Long): PdfiumDocument {
        val handleEpoch = docHandle and (0x7FFF_FFFFL shl 32)
        if (handleEpoch != processEpoch) {
            throw PdfInvalidHandleException("Stale or invalid document handle: $docHandle")
        }
        return activeDocuments[docHandle]
            ?: throw PdfInvalidHandleException("Unknown document handle: $docHandle")
    }

    private inline fun runOnRenderThread(crossinline block: (RenderResult) -> Unit): RenderResult {
        val result = RenderResult()
        try {
            renderExecutor.submit {
                try {
                    block(result)
                    result.errorCode = PdfiumException.ERROR_SUCCESS
                } catch (e: PdfiumException) {
                    result.errorCode = e.errorCode
                    result.errorMessage = e.message
                } catch (t: Throwable) {
                    result.errorCode = PdfiumException.ERROR_UNKNOWN
                    result.errorMessage = t.message ?: "Unknown render error"
                }
            }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            if (cause is PdfiumException) {
                result.errorCode = cause.errorCode
                result.errorMessage = cause.message
            } else {
                result.errorCode = PdfiumException.ERROR_UNKNOWN
                result.errorMessage = cause?.message ?: "Execution error"
            }
        } catch (t: Throwable) {
            result.errorCode = PdfiumException.ERROR_UNKNOWN
            result.errorMessage = t.message ?: "Unexpected service error"
        }
        return result
    }

    private val binder = object : IPdfiumRenderService.Stub() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val res = try {
                super.onTransact(code, data, reply, flags)
            } finally {
                pendingBufferCleanup.get()?.let {
                    runCatching { it.close() }
                    pendingBufferCleanup.remove()
                }
                pendingSharedMemCleanup.get()?.let {
                    runCatching { it.close() }
                    pendingSharedMemCleanup.remove()
                }
            }
            return res
        }

        override fun openDocument(pfd: ParcelFileDescriptor?, password: String?): RenderResult {
            if (pfd == null) {
                return RenderResult().apply {
                    errorCode = PdfiumException.ERROR_FILE
                    errorMessage = "ParcelFileDescriptor must not be null"
                }
            }
            return try {
                runOnRenderThread { result ->
                    pfd.use {
                        val doc = PdfiumEngine.openDocument(it.fd, password)
                        val handle = allocateHandle()
                        activeDocuments[handle] = doc
                        result.handle = handle
                    }
                }
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                RenderResult().apply {
                    errorCode = PdfiumException.ERROR_UNKNOWN
                    errorMessage = t.message ?: "Failed to open document"
                }
            }
        }

        override fun getPageCount(docHandle: Long): RenderResult {
            return runOnRenderThread { result ->
                val doc = getDocumentOrThrow(docHandle)
                result.pageCount = doc.pageCount
            }
        }

        override fun getPageSize(docHandle: Long, pageIndex: Int): RenderResult {
            return runOnRenderThread { result ->
                val doc = getDocumentOrThrow(docHandle)
                val size = doc.getPageSize(pageIndex)
                result.pageSize = floatArrayOf(size.widthPoints, size.heightPoints)
            }
        }

        override fun renderTile(
            docHandle: Long,
            pageIndex: Int,
            fullWidth: Int,
            fullHeight: Int,
            startX: Int,
            startY: Int,
            tileWidth: Int,
            tileHeight: Int,
            forceFallback: Boolean
        ): RenderResult {
            val renderResult = runOnRenderThread { result ->
                val doc = getDocumentOrThrow(docHandle)

                require(
                    tileWidth in 1..PdfiumDocument.MAX_TILE_DIMENSION &&
                        tileHeight in 1..PdfiumDocument.MAX_TILE_DIMENSION
                ) {
                    "Tile dimensions must be in 1..${PdfiumDocument.MAX_TILE_DIMENSION} ($tileWidth x $tileHeight)"
                }
                require(
                    fullWidth in 1..PdfiumDocument.MAX_PAGE_DIMENSION &&
                        fullHeight in 1..PdfiumDocument.MAX_PAGE_DIMENSION
                ) {
                    "Full dimensions must be in 1..${PdfiumDocument.MAX_PAGE_DIMENSION} ($fullWidth x $fullHeight)"
                }

                val shouldAttemptHardware = !forceFallback && !isHardwareTransportBroken
                var usedFallback = !shouldAttemptHardware

                if (shouldAttemptHardware) {
                    var buffer: HardwareBuffer? = null
                    try {
                        val usage = HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                        buffer = HardwareBuffer.create(
                            tileWidth,
                            tileHeight,
                            HardwareBuffer.RGBA_8888,
                            1,
                            usage
                        )
                        val status = doc.renderTileToHardwareBuffer(
                            pageIndex = pageIndex,
                            hardwareBuffer = buffer,
                            fullWidth = fullWidth,
                            fullHeight = fullHeight,
                            startX = startX,
                            startY = startY,
                            tileWidth = tileWidth,
                            tileHeight = tileHeight
                        )
                        when (status) {
                            PdfiumDocument.STATUS_SUCCESS -> {
                                result.hardwareBuffer = buffer
                                result.bufferWidth = tileWidth
                                result.bufferHeight = tileHeight
                            }
                            PdfiumDocument.STATUS_ERR_LOCK_FAILED,
                            PdfiumDocument.STATUS_ERR_UNSUPPORTED_FORMAT,
                            PdfiumDocument.STATUS_ERR_BUFFER_TOO_SMALL -> {
                                Log.w(
                                    TAG,
                                    "HardwareBuffer transport failed (status=$status). Falling back to SharedMemory."
                                )
                                isHardwareTransportBroken = true
                                buffer.close()
                                buffer = null
                                usedFallback = true
                            }
                            PdfiumDocument.STATUS_ERR_PAGE_NOT_FOUND -> {
                                buffer.close()
                                throw PdfPageNotFoundException("Page not found: $pageIndex")
                            }
                            PdfiumDocument.STATUS_ERR_INVALID_PARAM -> {
                                buffer.close()
                                throw IllegalArgumentException(
                                    "Invalid render parameters: page=$pageIndex, tile=${tileWidth}x$tileHeight"
                                )
                            }
                            else -> {
                                buffer.close()
                                throw PdfiumException(
                                    "Native tile rendering failed with status $status",
                                    PdfiumException.ERROR_UNKNOWN
                                )
                            }
                        }
                    } catch (e: PdfPageNotFoundException) {
                        throw e
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: PdfiumException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(
                            TAG,
                            "HardwareBuffer transport threw exception: ${t.message}. Falling back to SharedMemory.",
                            t
                        )
                        isHardwareTransportBroken = true
                        buffer?.let { runCatching { it.close() } }
                        usedFallback = true
                    }
                }

                if (usedFallback) {
                    Log.w(
                        TAG,
                        "Using SharedMemory fallback: forceFallback=$forceFallback, " +
                            "isHardwareTransportBroken=$isHardwareTransportBroken"
                    )
                    val bufferSizeLong = tileWidth.toLong() * tileHeight.toLong() * 4L
                    require(bufferSizeLong <= Int.MAX_VALUE) { "Tile buffer size exceeds Int.MAX_VALUE" }
                    val bufferSize = bufferSizeLong.toInt()
                    val sharedMemory = SharedMemory.create("pdfium_tile_${docHandle}_${pageIndex}", bufferSize)
                    try {
                        val byteBuffer = sharedMemory.mapReadWrite()
                        try {
                            val status = doc.renderTileToDirectBuffer(
                                pageIndex = pageIndex,
                                directBuffer = byteBuffer,
                                fullWidth = fullWidth,
                                fullHeight = fullHeight,
                                startX = startX,
                                startY = startY,
                                tileWidth = tileWidth,
                                tileHeight = tileHeight,
                                stride = tileWidth * 4
                            )
                            when (status) {
                                PdfiumDocument.STATUS_SUCCESS -> {
                                    // Successfully rendered into direct buffer
                                }
                                PdfiumDocument.STATUS_ERR_PAGE_NOT_FOUND -> {
                                    throw PdfPageNotFoundException("Page not found: $pageIndex")
                                }
                                PdfiumDocument.STATUS_ERR_INVALID_PARAM -> {
                                    throw IllegalArgumentException(
                                        "Invalid render parameters: page=$pageIndex, tile=${tileWidth}x$tileHeight"
                                    )
                                }
                                else -> {
                                    throw PdfiumException(
                                        "Native tile rendering to buffer failed with status $status",
                                        PdfiumException.ERROR_UNKNOWN
                                    )
                                }
                            }
                        } finally {
                            SharedMemory.unmap(byteBuffer)
                        }
                        sharedMemory.setProtect(OsConstants.PROT_READ)
                        result.sharedMemory = sharedMemory
                        result.bufferWidth = tileWidth
                        result.bufferHeight = tileHeight
                        result.bufferStride = tileWidth * 4
                    } catch (t: Throwable) {
                        runCatching { sharedMemory.close() }
                        throw t
                    }
                }
            }

            if (renderResult.errorCode == PdfiumException.ERROR_SUCCESS) {
                renderResult.hardwareBuffer?.let { pendingBufferCleanup.set(it) }
                renderResult.sharedMemory?.let { pendingSharedMemCleanup.set(it) }
            }
            return renderResult
        }

        override fun closeDocument(docHandle: Long) {
            renderExecutor.submit {
                val doc = activeDocuments.remove(docHandle)
                doc?.close()
            }
        }

        override fun getOpenDocumentCount(): Int {
            return activeDocuments.size
        }
    }


    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        renderExecutor.submit {
            activeDocuments.values.forEach { doc ->
                runCatching { doc.close() }
            }
            activeDocuments.clear()
            runCatching { PdfiumEngine.destroy() }
        }.get()
        renderExecutor.shutdown()
        super.onDestroy()
    }
}
