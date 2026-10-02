package app.terbaca.core.pdfium

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ServiceSpecificException
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

    private fun <T> java.util.concurrent.Future<T>.getUnwrapped(): T {
        try {
            return get()
        } catch (e: java.util.concurrent.ExecutionException) {
            when (val cause = e.cause) {
                is PdfiumException -> throw ServiceSpecificException(cause.errorCode, cause.message)
                is ServiceSpecificException -> throw cause
                else -> throw ServiceSpecificException(
                    PdfiumException.ERROR_UNKNOWN,
                    cause?.message ?: "Unknown render service error"
                )
            }
        }
    }

    private inline fun <T> runOnRenderThread(crossinline block: () -> T): T {
        return try {
            renderExecutor.submit<T> { block() }.getUnwrapped()
        } catch (e: ServiceSpecificException) {
            throw e
        } catch (t: Throwable) {
            throw ServiceSpecificException(
                PdfiumException.ERROR_UNKNOWN,
                t.message ?: "Unknown render service error"
            )
        }
    }

    private val binder = object : IPdfiumRenderService.Stub() {
        override fun openDocument(pfd: ParcelFileDescriptor?, password: String?): Long {
            if (pfd == null) {
                throw ServiceSpecificException(
                    PdfiumException.ERROR_FILE,
                    "ParcelFileDescriptor must not be null"
                )
            }
            return try {
                renderExecutor.submit<Long> {
                    pfd.use {
                        val doc = PdfiumEngine.openDocument(it.fd, password)
                        val handle = allocateHandle()
                        activeDocuments[handle] = doc
                        handle
                    }
                }.getUnwrapped()
            } catch (e: ServiceSpecificException) {
                runCatching { pfd.close() }
                throw e
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw ServiceSpecificException(
                    PdfiumException.ERROR_UNKNOWN,
                    t.message ?: "Unknown openDocument error"
                )
            }
        }

        override fun getPageCount(docHandle: Long): Int {
            return runOnRenderThread {
                val doc = getDocumentOrThrow(docHandle)
                doc.pageCount
            }
        }

        override fun getPageSize(docHandle: Long, pageIndex: Int): FloatArray {
            return runOnRenderThread {
                val doc = getDocumentOrThrow(docHandle)
                val size = doc.getPageSize(pageIndex)
                floatArrayOf(size.widthPoints, size.heightPoints)
            }
        }

        override fun closeDocument(docHandle: Long) {
            runOnRenderThread {
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
