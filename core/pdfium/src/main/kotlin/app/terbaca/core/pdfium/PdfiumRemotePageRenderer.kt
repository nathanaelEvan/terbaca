package app.terbaca.core.pdfium

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
        return withTimeoutOrNull(DEFAULT_CONNECT_TIMEOUT_MS) {
            connectionDeferred.await()
        } ?: throw PdfiumException("Render service connect timeout", PdfiumException.ERROR_UNKNOWN)
    }

    private fun RenderResult.unwrap(): RenderResult {
        if (errorCode != PdfiumException.ERROR_SUCCESS) {
            throw PdfiumException.fromErrorCode(errorCode, errorMessage)
        }
        return this
    }

    override suspend fun openDocument(pfd: ParcelFileDescriptor, password: String?): Long =
        withContext(Dispatchers.IO) {
            try {
                val service = getService()
                val result = service.openDocument(pfd, password).unwrap()
                result.handle
            } finally {
                runCatching { pfd.close() }
            }
        }

    override suspend fun getPageCount(docHandle: Long): Int = withContext(Dispatchers.IO) {
        val service = getService()
        val result = service.getPageCount(docHandle).unwrap()
        result.pageCount
    }

    override suspend fun getPageInfo(docHandle: Long, pageIndex: Int): PageInfo =
        withContext(Dispatchers.IO) {
            val service = getService()
            val result = service.getPageSize(docHandle, pageIndex).unwrap()
            val sizes = result.pageSize
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

    private val renderIdSequence = AtomicLong(
        (ThreadLocalRandom.current().nextLong(1L, 0x7FFF_FFFFL) shl 32) or 1L
    )

    override suspend fun closeDocument(docHandle: Long): Unit = withContext(Dispatchers.IO) {
        if (isServiceConnected) {
            runCatching {
                val service = getService()
                service.cancelAllTiles(docHandle)
                service.closeDocument(docHandle)
            }
        }
        Unit
    }

    override suspend fun renderTile(
        docHandle: Long,
        pageIndex: Int,
        fullWidth: Int,
        fullHeight: Int,
        startX: Int,
        startY: Int,
        tileWidth: Int,
        tileHeight: Int
    ): Bitmap = renderTile(
        renderId = renderIdSequence.getAndIncrement(),
        docHandle = docHandle,
        pageIndex = pageIndex,
        fullWidth = fullWidth,
        fullHeight = fullHeight,
        startX = startX,
        startY = startY,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        forceFallback = false
    )

    override suspend fun renderTile(
        renderId: Long,
        docHandle: Long,
        pageIndex: Int,
        fullWidth: Int,
        fullHeight: Int,
        startX: Int,
        startY: Int,
        tileWidth: Int,
        tileHeight: Int
    ): Bitmap = renderTile(
        renderId = renderId,
        docHandle = docHandle,
        pageIndex = pageIndex,
        fullWidth = fullWidth,
        fullHeight = fullHeight,
        startX = startX,
        startY = startY,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        forceFallback = false
    )

    suspend fun renderTile(
        renderId: Long = renderIdSequence.getAndIncrement(),
        docHandle: Long,
        pageIndex: Int,
        fullWidth: Int,
        fullHeight: Int,
        startX: Int,
        startY: Int,
        tileWidth: Int,
        tileHeight: Int,
        forceFallback: Boolean
    ): Bitmap = withContext(Dispatchers.IO) {
        val service = getService()
        val result = coroutineScope {
            var shouldCancelService = true
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    if (shouldCancelService) {
                        cancelTile(renderId)
                    }
                }
            }
            try {
                service.renderTile(
                    renderId,
                    docHandle,
                    pageIndex,
                    fullWidth,
                    fullHeight,
                    startX,
                    startY,
                    tileWidth,
                    tileHeight,
                    forceFallback
                ).unwrap()
            } finally {
                shouldCancelService = false
                watcher.cancel()
            }
        }

        val hwBuffer = result.hardwareBuffer
        if (hwBuffer != null) {
            try {
                val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, null)
                    ?: throw PdfiumException(
                        "Failed to wrap HardwareBuffer into Bitmap",
                        PdfiumException.ERROR_UNKNOWN
                    )
                bitmap
            } finally {
                hwBuffer.close()
            }
        } else {
            val sharedMem = result.sharedMemory
                ?: throw PdfiumException(
                    "RenderResult contained neither HardwareBuffer nor SharedMemory",
                    PdfiumException.ERROR_UNKNOWN
                )
            val width = result.bufferWidth
            val height = result.bufferHeight
            // Background copy to HARDWARE bitmap per PRD §4.3
            withContext(Dispatchers.Default) {
                try {
                    val readBuffer = sharedMem.mapReadOnly()
                    try {
                        var softwareBitmap: Bitmap? = null
                        try {
                            softwareBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            softwareBitmap.copyPixelsFromBuffer(readBuffer)
                            val hardwareBitmap = softwareBitmap.copy(Bitmap.Config.HARDWARE, false)
                                ?: throw PdfiumException(
                                    "Failed to copy software bitmap to HARDWARE config",
                                    PdfiumException.ERROR_UNKNOWN
                                )
                            hardwareBitmap
                        } finally {
                            softwareBitmap?.recycle()
                        }
                    } finally {
                        SharedMemory.unmap(readBuffer)
                    }
                } finally {
                    sharedMem.close()
                }
            }
        }
    }

    override fun cancelTile(renderId: Long) {
        if (isServiceConnected) {
            runCatching { serviceBinder?.cancelTile(renderId) }
        }
    }

    override fun cancelAllTiles(docHandle: Long) {
        if (isServiceConnected) {
            runCatching { serviceBinder?.cancelAllTiles(docHandle) }
        }
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
