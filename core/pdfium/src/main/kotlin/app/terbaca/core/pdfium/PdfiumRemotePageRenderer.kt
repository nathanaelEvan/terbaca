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
import app.terbaca.core.model.CharBox
import app.terbaca.core.model.OutlineItem
import app.terbaca.core.model.PageInfo
import app.terbaca.core.model.PageLink
import app.terbaca.core.model.PageRect
import app.terbaca.core.model.PageText
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.scheduler.CancellationToken
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        private const val MAX_CONSECUTIVE_CRASHES = 5
        private const val MAX_SESSION_CRASHES = 5
        private const val MAX_PAGE_CRASHES = 3
        private const val CRASH_WINDOW_MS = 10_000L
    }

    private data class DocumentSession(
        val clientHandle: Long,
        val savedPfd: ParcelFileDescriptor,
        val password: String?,
        @Volatile var remoteHandle: Long,
        @Volatile var failed: Boolean = false,
        @Volatile var consecutiveCrashes: Int = 0,
        @Volatile var lastCrashTimeMs: Long = 0L,
        val pageCrashes: ConcurrentHashMap<Int, Int> = ConcurrentHashMap()
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()
    private val isDeadNotified = AtomicBoolean(false)
    private val isRecovering = AtomicBoolean(false)

    private val consecutiveCrashes = AtomicInteger(0)
    private val lastCrashTimeMs = AtomicLong(0L)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val recoveryListeners = CopyOnWriteArrayList<() -> Unit>()
    private val openSessions = ConcurrentHashMap<Long, DocumentSession>()

    // renderId -> client docHandle for renders sent (or about to be sent) to the service.
    // cancelAllTiles cancels these by id, so a render still in transit can't miss the cancel.
    private val inFlightRenderIds = ConcurrentHashMap<Long, Long>()
    private val clientHandleSequence = AtomicLong(
        (ThreadLocalRandom.current().nextLong(1L, 0x7FFF_FFFFL) shl 32) or 1L
    )
    private val renderIdSequence = AtomicLong(
        (ThreadLocalRandom.current().nextLong(1L, 0x7FFF_FFFFL) shl 32) or 1L
    )

    private var serviceConnection: ServiceConnection? = null
    @Volatile private var activeBinder: IBinder? = null
    @Volatile private var serviceBinder: IPdfiumRenderService? = null
    @Volatile private var connectionDeferred = CompletableDeferred<IPdfiumRenderService>()

    @get:androidx.annotation.VisibleForTesting
    @set:androidx.annotation.VisibleForTesting
    var onRenderStarted: ((renderId: Long) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    internal fun getSessionCrashCount(docHandle: Long): Int =
        openSessions[docHandle]?.consecutiveCrashes ?: -1

    @androidx.annotation.VisibleForTesting
    internal fun getPageCrashCount(docHandle: Long, pageIndex: Int): Int =
        openSessions[docHandle]?.pageCrashes?.get(pageIndex) ?: 0

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

    private fun checkSessionActiveOrThrow(docHandle: Long, pageIndex: Int? = null) {
        val session = openSessions[docHandle] ?: return
        if (session.failed) {
            throw PdfiumException(
                "Document $docHandle is in a failed state due to repeated crashes",
                PdfiumException.ERROR_UNKNOWN
            )
        }
        if (pageIndex != null && (session.pageCrashes[pageIndex] ?: 0) >= MAX_PAGE_CRASHES) {
            throw PdfiumException(
                "Page $pageIndex of document $docHandle is in a failed state due to repeated crashes",
                PdfiumException.ERROR_UNKNOWN
            )
        }
    }

    private fun handleProcessDeath(deadBinder: IBinder? = null) {
        val didHandle = synchronized(lock) {
            val current = activeBinder
            if (current == null) {
                return
            }
            if (deadBinder != null && deadBinder !== current) {
                return
            }
            current.unlinkToDeath(deathRecipient, 0)
            activeBinder = null
            serviceBinder = null
            if (connectionDeferred.isCompleted) {
                connectionDeferred = CompletableDeferred()
            }
            true
        }
        if (!didHandle) return

        val now = System.currentTimeMillis()
        val last = lastCrashTimeMs.getAndSet(now)
        val crashes = if (last != 0L && now - last < CRASH_WINDOW_MS) {
            consecutiveCrashes.incrementAndGet()
        } else {
            consecutiveCrashes.set(1)
            1
        }

        openSessions.values.forEach { session ->
            val sessionLast = session.lastCrashTimeMs
            session.lastCrashTimeMs = now
            if (sessionLast != 0L && now - sessionLast < CRASH_WINDOW_MS) {
                session.consecutiveCrashes++
            } else {
                session.consecutiveCrashes = 1
            }
            if (session.consecutiveCrashes >= MAX_SESSION_CRASHES) {
                session.failed = true
                Log.e(TAG, "Document ${session.clientHandle} marked as failed after ${session.consecutiveCrashes} crashes")
            }
        }

        if (isDeadNotified.compareAndSet(false, true)) {
            onProcessDied?.invoke()
        }

        if (crashes > MAX_CONSECUTIVE_CRASHES) {
            Log.e(TAG, "Render process crash loop detected ($crashes crashes in window). Halting auto-recovery.")
            isRecovering.set(false)
            return
        }

        if (autoRecover && !isClosed && isRecovering.compareAndSet(false, true)) {
            scope.launch {
                val backoffMs = if (crashes > 1) {
                    minOf(500L * (1L shl (crashes - 2)), 5000L)
                } else {
                    0L
                }
                if (backoffMs > 0L) {
                    delay(backoffMs)
                }
                triggerRecovery()
            }
        }
    }

    private fun triggerRecovery() {
        synchronized(lock) {
            if (isClosed) return
            activeBinder?.unlinkToDeath(deathRecipient, 0)
            activeBinder = null
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
        val binder = proxy.asBinder()
        if (!binder.isBinderAlive) {
            Log.w(TAG, "handleServiceConnected called with dead binder; ignoring")
            return
        }

        synchronized(lock) {
            activeBinder = binder
        }

        if (autoRecover && openSessions.isNotEmpty()) {
            openSessions.values.forEach { session ->
                if (!session.failed) {
                    restoreSession(proxy, session)
                }
            }
        }

        if (!binder.isBinderAlive) {
            Log.w(TAG, "Binder died during session restoration; aborting recovery notification")
            handleProcessDeath(binder)
            return
        }

        synchronized(lock) {
            if (!binder.isBinderAlive) return
            activeBinder = binder
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
        var dupPfd: ParcelFileDescriptor? = null
        try {
            dupPfd = session.savedPfd.dup()
            val result = proxy.openDocument(dupPfd, session.password)
            if (result.errorCode == PdfiumException.ERROR_SUCCESS) {
                session.remoteHandle = result.handle
            } else {
                Log.w(TAG, "Failed to restore session ${session.clientHandle}: ${result.errorMessage}")
                session.failed = true
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException restoring session ${session.clientHandle}, will retry on reconnect", e)
        } catch (t: Throwable) {
            Log.w(TAG, "Exception restoring session ${session.clientHandle}", t)
        } finally {
            dupPfd?.let { runCatching { it.close() } }
        }
    }

    fun rebind() {
        synchronized(lock) {
            check(!isClosed) { "PageRenderer is closed" }
            activeBinder?.unlinkToDeath(deathRecipient, 0)
            activeBinder = null
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
        check(!isClosed) { "PageRenderer is closed" }
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
            var attempts = 0
            try {
                while (true) {
                    attempts++
                    val service = getService()
                    val pfdToSend = if (attempts == 1) {
                        pfd
                    } else {
                        savedPfd?.dup() ?: throw PdfiumException(
                            "ParcelFileDescriptor exhausted on retry",
                            PdfiumException.ERROR_FILE
                        )
                    }
                    try {
                        val result = service.openDocument(pfdToSend, password).unwrap()
                        val remoteHandle = result.handle
                        return@withContext if (autoRecover && savedPfd != null) {
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
                    } catch (e: RemoteException) {
                        handleProcessDeath(service.asBinder())
                        if (autoRecover && !isClosed && savedPfd != null && attempts <= MAX_RENDER_RETRIES) {
                            continue
                        }
                        throw PdfiumException(
                            "Render service IPC failure: ${e.message}",
                            PdfiumException.ERROR_UNKNOWN,
                            e
                        )
                    } finally {
                        if (attempts > 1) {
                            runCatching { pfdToSend.close() }
                        }
                    }
                }
            } catch (t: Throwable) {
                savedPfd?.let { runCatching { it.close() } }
                throw t
            } finally {
                runCatching { pfd.close() }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("Unreachable")
        }

    private suspend fun <T> executeWithRetry(block: suspend (IPdfiumRenderService) -> T): T {
        var attempts = 0
        while (true) {
            attempts++
            val service = getService()
            try {
                return block(service)
            } catch (e: RemoteException) {
                handleProcessDeath(service.asBinder())
                if (autoRecover && !isClosed && attempts <= MAX_RENDER_RETRIES) {
                    continue
                }
                throw PdfiumException("Render service IPC failure: ${e.message}", PdfiumException.ERROR_UNKNOWN, e)
            }
        }
    }

    override suspend fun getDocumentTitle(docHandle: Long): String? = withContext(Dispatchers.IO) {
        checkSessionActiveOrThrow(docHandle)
        executeWithRetry { service ->
            val remoteHandle = getRemoteHandle(docHandle)
            service.getDocumentTitle(remoteHandle)
        }
    }

    override suspend fun getPageText(docHandle: Long, pageIndex: Int): PageText = withContext(Dispatchers.IO) {
        checkSessionActiveOrThrow(docHandle, pageIndex)
        executeWithRetry { service ->
            val remoteHandle = getRemoteHandle(docHandle)
            val text = service.getPageText(remoteHandle, pageIndex) ?: ""
            val rawBoxes = service.getPageCharBoxes(remoteHandle, pageIndex) ?: FloatArray(0)
            val charBoxes = ArrayList<CharBox>(rawBoxes.size / 4)
            for (i in 0 until rawBoxes.size step 4) {
                charBoxes.add(CharBox(rawBoxes[i], rawBoxes[i + 1], rawBoxes[i + 2], rawBoxes[i + 3]))
            }
            PageText(pageIndex = pageIndex, text = text, charBoxes = charBoxes)
        }
    }

    override suspend fun getPageLinks(docHandle: Long, pageIndex: Int): List<PageLink> = withContext(Dispatchers.IO) {
        checkSessionActiveOrThrow(docHandle, pageIndex)
        executeWithRetry { service ->
            val remoteHandle = getRemoteHandle(docHandle)
            val parcels = service.getPageLinks(remoteHandle, pageIndex) ?: emptyList()
            parcels.map { p ->
                val sourceRect = PageRect(p.left, p.top, p.right, p.bottom)
                PageLink(
                    sourceRect = sourceRect,
                    targetPageIndex = p.targetPageIndex,
                    targetX = if (p.targetX.isNaN()) null else p.targetX,
                    targetY = if (p.targetY.isNaN()) null else p.targetY,
                    uri = if (p.uri.isNullOrEmpty()) null else p.uri
                )
            }
        }
    }

    override suspend fun getOutline(docHandle: Long): List<OutlineItem> = withContext(Dispatchers.IO) {
        checkSessionActiveOrThrow(docHandle)
        executeWithRetry { service ->
            val remoteHandle = getRemoteHandle(docHandle)
            val parcels = service.getOutline(remoteHandle) ?: emptyList()
            buildOutlineTree(parcels)
        }
    }

    override suspend fun getImageBounds(docHandle: Long, pageIndex: Int): List<PageRect> = withContext(Dispatchers.IO) {
        checkSessionActiveOrThrow(docHandle, pageIndex)
        executeWithRetry { service ->
            val remoteHandle = getRemoteHandle(docHandle)
            val raw = service.getImageBounds(remoteHandle, pageIndex) ?: FloatArray(0)
            val result = ArrayList<PageRect>(raw.size / 4)
            for (i in 0 until raw.size step 4) {
                result.add(PageRect(raw[i], raw[i + 1], raw[i + 2], raw[i + 3]))
            }
            result
        }
    }

    override suspend fun getPageCount(docHandle: Long): Int = withContext(Dispatchers.IO) {
        checkSessionActiveOrThrow(docHandle)
        executeWithRetry { service ->
            val remoteHandle = getRemoteHandle(docHandle)
            val result = service.getPageCount(remoteHandle).unwrap()
            result.pageCount
        }
    }

    override suspend fun getPageInfo(docHandle: Long, pageIndex: Int): PageInfo =
        withContext(Dispatchers.IO) {
            checkSessionActiveOrThrow(docHandle, pageIndex)
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
            checkSessionActiveOrThrow(docHandle)
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
        forceFallback = false,
        cancellationToken = null
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
        forceFallback = false,
        cancellationToken = null
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
        tileHeight: Int,
        cancellationToken: CancellationToken?
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
        forceFallback = false,
        cancellationToken = cancellationToken
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
        forceFallback: Boolean,
        cancellationToken: CancellationToken? = null
    ): Bitmap = withContext(Dispatchers.IO) {
        checkSessionActiveOrThrow(docHandle, pageIndex)
        if (cancellationToken?.isCancelled() == true) {
            cancelTile(renderId)
            throw CancellationException("Render cancelled before dispatch: renderId=$renderId")
        }
        var attempts = 0
        while (true) {
            attempts++
            val service = getService()
            val remoteHandle = getRemoteHandle(docHandle)
            var rawResult: RenderResult? = null
            var bitmap: Bitmap? = null
            try {
                val result = coroutineScope {
                    val renderFinished = AtomicBoolean(false)
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            if (cancellationToken != null) {
                                while (!renderFinished.get() && !cancellationToken.isCancelled()) {
                                    kotlinx.coroutines.delay(10)
                                }
                                if (!renderFinished.get() && cancellationToken.isCancelled()) {
                                    cancelTile(renderId)
                                }
                            } else {
                                awaitCancellation()
                            }
                        } finally {
                            if (!renderFinished.get()) {
                                cancelTile(renderId)
                            }
                        }
                    }
                    try {
                        inFlightRenderIds[renderId] = docHandle
                        onRenderStarted?.invoke(renderId)
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
                        ).unwrap().also { rawResult = it }
                    } catch (e: PdfRenderCancelledException) {
                        if (!currentCoroutineContext().isActive || cancellationToken?.isCancelled() == true) {
                            throw CancellationException("Render cancelled: renderId=$renderId", e)
                        }
                        throw e
                    } finally {
                        inFlightRenderIds.remove(renderId)
                        renderFinished.set(true)
                        watcher.cancel()
                    }
                }
                bitmap = unpackRenderResult(result)
                consecutiveCrashes.set(0)
                openSessions[docHandle]?.let { session ->
                    session.consecutiveCrashes = 0
                    session.pageCrashes.remove(pageIndex)
                }
                rawResult = null
                return@withContext bitmap
            } catch (t: Throwable) {
                rawResult?.hardwareBuffer?.let { runCatching { it.close() } }
                rawResult?.sharedMemory?.let { runCatching { it.close() } }
                if (t is CancellationException) {
                    bitmap?.let { runCatching { it.recycle() } }
                }
                if (t is RemoteException) {
                    handleProcessDeath(service.asBinder())
                    val session = openSessions[docHandle]
                    val crashes = session?.pageCrashes?.merge(pageIndex, 1, Int::plus) ?: 1
                    if (crashes >= MAX_PAGE_CRASHES) {
                        Log.e(TAG, "Page $pageIndex of document $docHandle marked as failed after $crashes crashes")
                        throw PdfiumException(
                            "Page $pageIndex of document $docHandle is in a failed state due to repeated crashes",
                            PdfiumException.ERROR_UNKNOWN,
                            t
                        )
                    }
                    if (autoRecover && !isClosed && attempts <= MAX_RENDER_RETRIES) {
                        continue
                    }
                    throw PdfiumException("Render service IPC failure: ${t.message}", PdfiumException.ERROR_UNKNOWN, t)
                }
                throw t
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
            // The service-side cancel only reaches renders it has already received. Also cancel
            // every render this client has sent by id: one that arrives later finds its id in the
            // service's pending-cancellation set and stops before it starts.
            inFlightRenderIds.forEach { (renderId, handle) ->
                if (handle == docHandle) cancelTile(renderId)
            }
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
            synchronized(lock) {
                openSessions.values.forEach { session ->
                    runCatching { session.savedPfd.close() }
                }
                openSessions.clear()
                recoveryListeners.clear()
                activeBinder?.unlinkToDeath(deathRecipient, 0)
                activeBinder = null
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

internal fun buildOutlineTree(parcels: List<OutlineItemParcel>): List<OutlineItem> {
    if (parcels.isEmpty()) return emptyList()

    class Node(
        val title: String,
        val pageIndex: Int,
        val destX: Float?,
        val destY: Float?,
        val depth: Int,
        val children: MutableList<Node> = mutableListOf()
    ) {
        fun toOutlineItem(): OutlineItem = OutlineItem(
            title = title,
            pageIndex = pageIndex,
            destX = destX,
            destY = destY,
            children = children.map { it.toOutlineItem() }
        )
    }

    val roots = mutableListOf<Node>()
    val stack = ArrayDeque<Node>()

    for (p in parcels) {
        val destX = if (p.destX.isNaN()) null else p.destX
        val destY = if (p.destY.isNaN()) null else p.destY
        val node = Node(p.title ?: "", p.pageIndex, destX, destY, p.depth)

        while (stack.isNotEmpty() && stack.last().depth >= node.depth) {
            stack.removeLast()
        }

        if (stack.isEmpty()) {
            roots.add(node)
        } else {
            stack.last().children.add(node)
        }
        stack.addLast(node)
    }

    return roots.map { it.toOutlineItem() }
}
