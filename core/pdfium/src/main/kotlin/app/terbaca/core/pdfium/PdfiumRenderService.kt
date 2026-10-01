package app.terbaca.core.pdfium

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Android Service hosting PDFium native operations inside a dedicated ":render" process.
 * All calls are strictly serialized onto a single render thread per PRD §4.2.
 */
class PdfiumRenderService : Service() {

    private val activeDocuments = ConcurrentHashMap<Long, PdfiumDocument>()
    private val nextHandle = AtomicLong(1L)

    // Dedicated single render thread as required by PRD §4.2
    private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PdfiumRenderThread").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    
    private fun <T> java.util.concurrent.Future<T>.getUnwrapped(): T {
        try {
            return get()
        } catch (e: java.util.concurrent.ExecutionException) {
            when (val cause = e.cause) {
                is PdfPasswordException -> throw SecurityException("PASSWORD_REQUIRED: ${cause.message}", cause)
                is PdfCorruptException -> throw IllegalArgumentException("CORRUPT_FORMAT: ${cause.message}", cause)
                is PdfFileException -> throw IllegalArgumentException("FILE_ERROR: ${cause.message}", cause)
                is RuntimeException -> throw cause
                else -> throw IllegalStateException("Operation failed: ${cause?.message}", cause)
            }
        }
    }

    private val binder = object : IPdfiumRenderService.Stub() {
        override fun openDocument(pfd: ParcelFileDescriptor?, password: String?): Long {
            requireNotNull(pfd) { "ParcelFileDescriptor must not be null" }
            return renderExecutor.submit<Long> {
                val fd = pfd.detachFd()
                val doc = PdfiumEngine.openDocument(fd, password)
                val handle = nextHandle.getAndIncrement()
                activeDocuments[handle] = doc
                handle
            }.getUnwrapped()
        }

        override fun getPageCount(docHandle: Long): Int {
            return renderExecutor.submit<Int> {
                val doc = activeDocuments[docHandle]
                    ?: throw IllegalArgumentException("Unknown document handle: $docHandle")
                doc.pageCount
            }.getUnwrapped()
        }

        override fun getPageSize(docHandle: Long, pageIndex: Int): FloatArray {
            return renderExecutor.submit<FloatArray> {
                val doc = activeDocuments[docHandle]
                    ?: throw IllegalArgumentException("Unknown document handle: $docHandle")
                val size = doc.getPageSize(pageIndex)
                floatArrayOf(size.widthPoints, size.heightPoints)
            }.getUnwrapped()
        }

        override fun closeDocument(docHandle: Long) {
            renderExecutor.submit {
                val doc = activeDocuments.remove(docHandle)
                doc?.close()
            }.getUnwrapped()
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
