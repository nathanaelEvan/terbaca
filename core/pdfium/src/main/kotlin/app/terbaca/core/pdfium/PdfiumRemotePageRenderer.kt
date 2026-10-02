package app.terbaca.core.pdfium

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ServiceSpecificException
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete [PageRenderer] implementation communicating with [PdfiumRenderService]
 * across the process boundary via Binder IPC (AIDL).
 */
class PdfiumRemotePageRenderer(
    context: Context,
    private val onProcessDied: (() -> Unit)? = null
) : PageRenderer {

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private val isClosed = AtomicBoolean(false)
    private val isDeadNotified = AtomicBoolean(false)

    private var serviceConnection: ServiceConnection? = null
    @Volatile private var serviceBinder: IPdfiumRenderService? = null
    @Volatile private var connectionDeferred = CompletableDeferred<IPdfiumRenderService>()

    private val deathRecipient = IBinder.DeathRecipient {
        handleProcessDeath()
    }

    override val isServiceConnected: Boolean
        get() = serviceBinder?.asBinder()?.isBinderAlive == true

    init {
        bind()
    }

    private fun handleProcessDeath() {
        synchronized(lock) {
            serviceBinder = null
            if (connectionDeferred.isCompleted) {
                connectionDeferred = CompletableDeferred()
            }
        }
        if (isDeadNotified.compareAndSet(false, true)) {
            onProcessDied?.invoke()
        }
    }

    private fun bind() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) {
                    runCatching { service.linkToDeath(deathRecipient, 0) }
                    val proxy = IPdfiumRenderService.Stub.asInterface(service)
                    synchronized(lock) {
                        serviceBinder = proxy
                        if (!connectionDeferred.isCompleted) {
                            connectionDeferred.complete(proxy)
                        }
                    }
                    isDeadNotified.set(false)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handleProcessDeath()
            }
        }
        serviceConnection = connection
        val intent = Intent(appContext, PdfiumRenderService::class.java)
        val bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            val error = IllegalStateException(
                "Failed to bind to PdfiumRenderService: bindService returned false"
            )
            connectionDeferred.completeExceptionally(error)
        }
    }

    fun rebind() {
        synchronized(lock) {
            check(!isClosed.get()) { "PageRenderer is closed" }
            serviceBinder?.asBinder()?.unlinkToDeath(deathRecipient, 0)
            serviceBinder = null
            serviceConnection?.let {
                runCatching { appContext.unbindService(it) }
            }
            if (connectionDeferred.isCompleted) {
                connectionDeferred = CompletableDeferred()
            }
            isDeadNotified.set(false)
            bind()
        }
    }

    private suspend fun getService(): IPdfiumRenderService {
        check(!isClosed.get()) { "PageRenderer is closed" }
        serviceBinder?.let { return it }
        return withTimeout(DEFAULT_CONNECT_TIMEOUT_MS) {
            connectionDeferred.await()
        }
    }

    private inline fun <T> wrapServiceExceptions(block: () -> T): T {
        try {
            return block()
        } catch (e: ServiceSpecificException) {
            throw PdfiumException.fromErrorCode(e.errorCode, e.message)
        }
    }

    override suspend fun openDocument(pfd: ParcelFileDescriptor, password: String?): Long =
        withContext(Dispatchers.IO) {
            try {
                val service = getService()
                wrapServiceExceptions {
                    service.openDocument(pfd, password)
                }
            } finally {
                runCatching { pfd.close() }
            }
        }

    override suspend fun getPageCount(docHandle: Long): Int = withContext(Dispatchers.IO) {
        val service = getService()
        wrapServiceExceptions {
            service.getPageCount(docHandle)
        }
    }

    override suspend fun getPageInfo(docHandle: Long, pageIndex: Int): PageInfo =
        withContext(Dispatchers.IO) {
            val service = getService()
            val sizes = wrapServiceExceptions {
                service.getPageSize(docHandle, pageIndex)
            }
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
            runCatching {
                wrapServiceExceptions {
                    getService().closeDocument(docHandle)
                }
            }
        }
        Unit
    }

    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            synchronized(lock) {
                serviceBinder?.asBinder()?.unlinkToDeath(deathRecipient, 0)
                serviceBinder = null
                serviceConnection?.let {
                    runCatching { appContext.unbindService(it) }
                }
                serviceConnection = null
                if (!connectionDeferred.isCompleted) {
                    connectionDeferred.cancel()
                }
            }
        }
    }
}
