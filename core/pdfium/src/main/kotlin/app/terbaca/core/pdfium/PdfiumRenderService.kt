package app.terbaca.core.pdfium

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Android Service hosting PDFium native operations inside a dedicated ":render" process.
 * All calls are strictly serialized onto a single render thread per PRD §4.2.
 */
class PdfiumRenderService : Service() {

    private val activeDocuments = ConcurrentHashMap<Long, PdfiumDocument>()

    // High 32 bits contain a random positive epoch unique to this process lifetime.
    // Mask with 0x7FFF_FFFF to ensure positive signed Long.
    private val processEpoch: Long =
        (ThreadLocalRandom.current().nextLong(1L, 0x7FFF_FFFFL) shl 32)
    private val sequence = AtomicLong(1L)

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
