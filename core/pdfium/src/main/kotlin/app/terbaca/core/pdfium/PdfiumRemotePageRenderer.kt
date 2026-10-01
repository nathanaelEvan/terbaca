package app.terbaca.core.pdfium

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete [PageRenderer] implementation communicating with [PdfiumRenderService]
 * across the process boundary via Binder IPC (AIDL).
 */
class PdfiumRemotePageRenderer(
    context: Context,
    private val onProcessDied: (() -> Unit)? = null
) : PageRenderer {

    private val appContext = context.applicationContext
    private var serviceConnection: ServiceConnection? = null
    private var serviceBinder: IPdfiumRenderService? = null
    private var connectionDeferred = CompletableDeferred<IPdfiumRenderService>()
    private val isClosed = AtomicBoolean(false)

    private val deathRecipient = IBinder.DeathRecipient {
        serviceBinder = null
        connectionDeferred = CompletableDeferred()
        onProcessDied?.invoke()
    }

    override val isServiceConnected: Boolean
        get() = serviceBinder?.asBinder()?.isBinderAlive == true

    init {
        bind()
    }

    private fun bind() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) {
                    service.linkToDeath(deathRecipient, 0)
                    val proxy = IPdfiumRenderService.Stub.asInterface(service)
                    serviceBinder = proxy
                    if (!connectionDeferred.isCompleted) {
                        connectionDeferred.complete(proxy)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBinder = null
                if (!connectionDeferred.isCompleted) {
                    connectionDeferred = CompletableDeferred()
                }
                onProcessDied?.invoke()
            }
        }
        serviceConnection = connection
        val intent = Intent(appContext, PdfiumRenderService::class.java)
        appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private suspend fun getService(): IPdfiumRenderService {
        check(!isClosed.get()) { "PageRenderer is closed" }
        serviceBinder?.let { return it }
        return connectionDeferred.await()
    }

    override suspend fun openDocument(pfd: ParcelFileDescriptor, password: String?): Long =
        withContext(Dispatchers.IO) {
            val service = getService()
            try {
                service.openDocument(pfd, password)
            } catch (e: SecurityException) {
                throw PdfPasswordException(e.message ?: "Password required or invalid")
            } catch (e: IllegalArgumentException) {
                if (e.message?.contains("CORRUPT") == true) {
                    throw PdfCorruptException(e.message ?: "Corrupt PDF")
                } else {
                    throw PdfFileException(e.message ?: "File error")
                }
            }
        }

    override suspend fun getPageCount(docHandle: Long): Int = withContext(Dispatchers.IO) {
        getService().getPageCount(docHandle)
    }

    override suspend fun getPageInfo(docHandle: Long, pageIndex: Int): PageInfo =
        withContext(Dispatchers.IO) {
            val sizes = getService().getPageSize(docHandle, pageIndex)
            PageInfo(
                index = pageIndex,
                widthPoints = sizes[0],
                heightPoints = sizes[1],
            )
        }

    override suspend fun getAllPages(docHandle: Long): List<PageInfo> =
        withContext(Dispatchers.IO) {
            val count = getPageCount(docHandle)
            (0 until count).map { getPageInfo(docHandle, it) }
        }

    override suspend fun closeDocument(docHandle: Long): Unit = withContext(Dispatchers.IO) {
        if (isServiceConnected) {
            runCatching { getService().closeDocument(docHandle) }
        }
        Unit
    }

    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            serviceBinder?.asBinder()?.unlinkToDeath(deathRecipient, 0)
            serviceBinder = null
            serviceConnection?.let {
                runCatching { appContext.unbindService(it) }
            }
            serviceConnection = null
        }
    }
}
