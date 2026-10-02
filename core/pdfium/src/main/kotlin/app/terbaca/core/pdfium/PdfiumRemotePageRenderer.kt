package app.terbaca.core.pdfium

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import android.util.Log
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Concrete [PageRenderer] implementation communicating with [PdfiumRenderService]
 * across the process boundary via Binder IPC (AIDL).
 *
 * Implements automated process crash recovery, transparent session restoration, and tile
 * re-rendering per PRD §4.2 and M1-09.
 */
class PdfiumRemotePageRenderer(
    context: Context,
    private val onProcessDied: (() -> Unit)? = null,
    private val autoRecover: Boolean = true,
    private val onProcessRecovered: (() -> Unit)? = null
) : PageRenderer {

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        private const val TAG = "PdfiumRemoteRenderer"
        private const val MAX_RENDER_RETRIES = 1
    }

    private data class DocumentSession(
        val clientHandle: Long,
        val savedPfd: ParcelFileDescriptor,
        val password: String?,
        @Volatile var remoteHandle: Long
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val isClosed = AtomicBoolean(false)
    private val isDeadNotified = AtomicBoolean(false)
    private val isRecovering = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val recoveryListeners = CopyOnWriteArrayList<() -> Unit>()
    private val openSessions = ConcurrentHashMap<Long, DocumentSession>()
    private val clientHandleSequence = AtomicLong(
        (ThreadLocalRandom.current().nextLong(1L, 0x7FFF_FFFFL) shl 32) or 1L
    )
    private val renderIdSequence = AtomicLong(
        (ThreadLocalRandom.current().nextLong(1L, 0x7FFF_FFFFL) shl 32) or 1L
    )

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

    override fun registerRecoveryListener(listener: () -> Unit): () -> Unit {
        recoveryListeners.add(listener)
        return { recoveryListeners.remove(listener) }
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
        if (autoRecover && !isClosed.get() && isRecovering.compareAndSet(false, true)) {
            scope.launch {
                triggerRecovery()
            }
        }
    }

    private fun triggerRecovery() {
        synchronized(lock) {
            if (isClosed.get()) return
            serviceBinder?.asBinder()?.unlinkToDeath(deathRecipient, 0)
            serviceBinder = null
            serviceConnection?.let {
                runCatching { appContext.unbindService(it) }
            }
            serviceConnection = null
            bind()
        }
    }

    private fun bind() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) {
                    runCatching { service.linkToDeath(deathRecipient, 0) }
                    val proxy = IPdfiumRenderService.Stub.asInterface(service)
                    scope.launch {
                        handleServiceConnected(proxy)
                    }
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

    private suspend fun handleServiceConnected(proxy: IPdfiumRenderService) {
        if (autoRecover && openSessions.isNotEmpty()) {
            openSessions.values.forEach { session ->
                restoreSession(proxy, session)
            }
        }
        synchronized(lock) {
            serviceBinder = proxy
            if (!connectionDeferred.isCompleted) {
                connectionDeferred.complete(proxy)
            }
        }
        isDeadNotified.set(false)
        isRecovering.set(false)

        onProcessRecovered?.invoke()
        recoveryListeners.forEach { runCatching { it.invoke() } }
    }

    private fun restoreSession(proxy: IPdfiumRenderService, session: DocumentSession) {
        try {
            val dupPfd = session.savedPfd.dup()
            val result = proxy.openDocument(dupPfd, session.password)
            if (result.errorCode == PdfiumException.ERROR_SUCCESS) {
                session.remoteHandle = result.handle
            } else {
                Log.w(TAG, "Failed to restore session ${session.clientHandle}: ${result.errorMessage}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Exception restoring session ${session.clientHandle}", t)
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
            serviceConnection = null
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

    private fun getRemoteHandle(docHandle: Long): Long {
        return openSessions[docHandle]?.remoteHandle ?: docHandle
    }

    override suspend fun openDocument(pfd: ParcelFileDescriptor, password: String?): Long =
        withContext(Dispatchers.IO) {
            val savedPfd = if (autoRecover) {
                try {
                    pfd.dup()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to dup ParcelFileDescriptor; auto-recovery disabled for document", e)
                    null
                }
            } else {
                null
            }
            try {
                val service = getService()
                val result = service.openDocument(pfd, password).unwrap()
                val remoteHandle = result.handle
                if (autoRecover && savedPfd != null) {
                    val clientHandle = clientHandleSequence.getAndIncrement()
                    openSessions[clientHandle] = DocumentSession(
                        clientHandle = clientHandle,
                        savedPfd = savedPfd,
                        password = password,
                        remoteHandle = remoteHandle
                    )
                    clientHandle
                } else {
                    savedPfd?.let { runCatching { it.close() } }
                    remoteHandle
                }
            } catch (t: Throwable) {
                savedPfd?.let { runCatching { it.close() } }
                throw t
            } finally {
                runCatching { pfd.close() }
            }
        }

    private suspend fun <T> executeWithRetry(block: suspend (IPdfiumRenderService) -> T): T {
        var attempts = 0
        while (true) {
            attempts++
            val service = getService()
            try {
                return block(service)
            } catch (e: RemoteException) {
                handleProcessDeath()
                if (autoRecover && !isClosed.get() && attempts <= MAX_RENDER_RETRIES) {
                    continue
                }
                throw PdfiumException("Render service IPC failure: ${e.message}", PdfiumException.ERROR_UNKNOWN, e)
            }
        }
    }

    override suspend fun getPageCount(docHandle: Long): Int = withContext(Dispatchers.IO) {
        executeWithRetry { service ->
            val remoteHandle = getRemoteHandle(docHandle)
            val result = service.getPageCount(remoteHandle).unwrap()
            result.pageCount
        }
    }

    override suspend fun getPageInfo(docHandle: Long, pageIndex: Int): PageInfo =
        withContext(Dispatchers.IO) {
            executeWithRetry { service ->
                val remoteHandle = getRemoteHandle(docHandle)
                val result = service.getPageSize(remoteHandle, pageIndex).unwrap()
                val sizes = result.pageSize
                PageInfo(
                    index = pageIndex,
                    widthPoints = sizes[0],
                    heightPoints = sizes[1],
                )
            }
        }

    override suspend fun getAllPages(docHandle: Long): List<PageInfo> =
        withContext(Dispatchers.IO) {
            val count = getPageCount(docHandle)
            (0 until count).map { getPageInfo(docHandle, it) }
        }

    override suspend fun closeDocument(docHandle: Long): Unit = withContext(Dispatchers.IO) {
        val session = openSessions.remove(docHandle)
        val remoteHandle = session?.remoteHandle ?: docHandle
        session?.let { runCatching { it.savedPfd.close() } }
        if (isServiceConnected) {
            runCatching {
                val service = getService()
                service.cancelAllTiles(remoteHandle)
                service.closeDocument(remoteHandle)
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
        var attempts = 0
        while (true) {
            attempts++
            val service = getService()
            val remoteHandle = getRemoteHandle(docHandle)
            try {
                val result = coroutineScope {
                    val renderFinished = AtomicBoolean(false)
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            if (!renderFinished.get()) {
                                cancelTile(renderId)
                            }
                        }
                    }
                    try {
                        service.renderTile(
                            renderId,
                            remoteHandle,
                            pageIndex,
                            fullWidth,
                            fullHeight,
                            startX,
                            startY,
                            tileWidth,
                            tileHeight,
                            forceFallback
                        ).unwrap()
                    } catch (e: PdfRenderCancelledException) {
                        if (!currentCoroutineContext().isActive) {
                            throw CancellationException("Render cancelled: renderId=$renderId", e)
                        }
                        throw e
                    } finally {
                        renderFinished.set(true)
                        watcher.cancel()
                    }
                }
                return@withContext unpackRenderResult(result)
            } catch (e: RemoteException) {
                handleProcessDeath()
                if (autoRecover && !isClosed.get() && attempts <= MAX_RENDER_RETRIES) {
                    continue
                }
                throw PdfiumException("Render service IPC failure: ${e.message}", PdfiumException.ERROR_UNKNOWN, e)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("Unreachable")
    }

    private suspend fun unpackRenderResult(result: RenderResult): Bitmap {
        val hwBuffer = result.hardwareBuffer
        if (hwBuffer != null) {
            try {
                return Bitmap.wrapHardwareBuffer(hwBuffer, null)
                    ?: throw PdfiumException(
                        "Failed to wrap HardwareBuffer into Bitmap",
                        PdfiumException.ERROR_UNKNOWN
                    )
            } finally {
                hwBuffer.close()
            }
        }
        val sharedMem = result.sharedMemory
            ?: throw PdfiumException(
                "RenderResult contained neither HardwareBuffer nor SharedMemory",
                PdfiumException.ERROR_UNKNOWN
            )
        val width = result.bufferWidth
        val height = result.bufferHeight
        return withContext(Dispatchers.Default) {
            try {
                val readBuffer = sharedMem.mapReadOnly()
                try {
                    var softwareBitmap: Bitmap? = null
                    try {
                        softwareBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        softwareBitmap.copyPixelsFromBuffer(readBuffer)
                        softwareBitmap.copy(Bitmap.Config.HARDWARE, false)
                            ?: throw PdfiumException(
                                "Failed to copy software bitmap to HARDWARE config",
                                PdfiumException.ERROR_UNKNOWN
                            )
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

    override fun cancelTile(renderId: Long) {
        if (isServiceConnected) {
            runCatching { serviceBinder?.cancelTile(renderId) }
        }
    }

    override fun cancelAllTiles(docHandle: Long) {
        if (isServiceConnected) {
            val remoteHandle = getRemoteHandle(docHandle)
            runCatching { serviceBinder?.cancelAllTiles(remoteHandle) }
        }
    }

    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            scope.cancel()
            synchronized(lock) {
                openSessions.values.forEach { session ->
                    runCatching { session.savedPfd.close() }
                }
                openSessions.clear()
                recoveryListeners.clear()
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
