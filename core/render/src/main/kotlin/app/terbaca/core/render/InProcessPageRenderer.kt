package app.terbaca.core.render

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import app.terbaca.core.model.OutlineItem
import app.terbaca.core.model.PageInfo
import app.terbaca.core.model.PageLink
import app.terbaca.core.model.PageRect
import app.terbaca.core.model.PageText
import app.terbaca.core.render.scheduler.CancellationToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process mock implementation of [PageRenderer] for unit testing and M1 prototyping per PRD §4.2.
 */
open class InProcessPageRenderer(
    private val defaultPageCount: Int = 1,
    private val defaultWidthPoints: Float = 612f,
    private val defaultHeightPoints: Float = 792f,
    val renderDelayMs: Long = 0L,
    val onRenderStarted: ((renderId: Long) -> Unit)? = null,
    private val bitmapSupplier: (width: Int, height: Int) -> Bitmap = { w, h ->
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }
) : PageRenderer {

    private val documents = ConcurrentHashMap<Long, List<PageInfo>>()
    private val documentTitles = ConcurrentHashMap<Long, String>()
    private val documentOutlines = ConcurrentHashMap<Long, List<OutlineItem>>()
    private val pageTexts = ConcurrentHashMap<Pair<Long, Int>, PageText>()
    private val pageLinks = ConcurrentHashMap<Pair<Long, Int>, List<PageLink>>()
    private val pageImageBounds = ConcurrentHashMap<Pair<Long, Int>, List<PageRect>>()
    private val nextHandle = AtomicLong(1L)
    private val recoveryListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    val cancelledRenderIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun setOutline(docHandle: Long, outline: List<OutlineItem>) {
        documentOutlines[docHandle] = outline
    }

    fun setPageText(docHandle: Long, pageIndex: Int, pageText: PageText) {
        pageTexts[Pair(docHandle, pageIndex)] = pageText
    }

    fun setPageLinks(docHandle: Long, pageIndex: Int, links: List<PageLink>) {
        pageLinks[Pair(docHandle, pageIndex)] = links
    }

    fun setImageBounds(docHandle: Long, pageIndex: Int, bounds: List<PageRect>) {
        pageImageBounds[Pair(docHandle, pageIndex)] = bounds
    }

    override var isServiceConnected: Boolean = true

    override fun cancelTile(renderId: Long) {
        cancelledRenderIds.add(renderId)
    }

    override fun registerRecoveryListener(listener: () -> Unit): () -> Unit {
        recoveryListeners.add(listener)
        return { recoveryListeners.remove(listener) }
    }

    fun simulateRecovery() {
        recoveryListeners.forEach { it.invoke() }
    }

    fun createTestDocument(
        pageCount: Int = defaultPageCount,
        widthPoints: Float = defaultWidthPoints,
        heightPoints: Float = defaultHeightPoints,
        title: String? = null
    ): Long {
        check(isServiceConnected) { "Service is disconnected" }
        val handle = nextHandle.getAndIncrement()
        val pages = (0 until pageCount).map { i ->
            PageInfo(index = i, widthPoints = widthPoints, heightPoints = heightPoints)
        }
        documents[handle] = pages
        if (title != null) {
            documentTitles[handle] = title
        }
        return handle
    }

    override suspend fun openDocument(pfd: ParcelFileDescriptor, password: String?): Long {
        check(isServiceConnected) { "Service is disconnected" }
        runCatching { pfd.close() }
        return createTestDocument()
    }

    override suspend fun getPageCount(docHandle: Long): Int {
        check(isServiceConnected) { "Service is disconnected" }
        val pages = documents[docHandle] ?: error("Invalid document handle: $docHandle")
        return pages.size
    }

    override suspend fun getPageInfo(docHandle: Long, pageIndex: Int): PageInfo {
        check(isServiceConnected) { "Service is disconnected" }
        val pages = documents[docHandle] ?: error("Invalid document handle: $docHandle")
        return pages.getOrNull(pageIndex) ?: error("Page index out of bounds: $pageIndex")
    }

    override suspend fun getAllPages(docHandle: Long): List<PageInfo> {
        check(isServiceConnected) { "Service is disconnected" }
        return documents[docHandle] ?: error("Invalid document handle: $docHandle")
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
        renderId = nextHandle.getAndIncrement(),
        docHandle = docHandle,
        pageIndex = pageIndex,
        fullWidth = fullWidth,
        fullHeight = fullHeight,
        startX = startX,
        startY = startY,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
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
    ): Bitmap = coroutineScope {
        check(isServiceConnected) { "Service is disconnected" }
        getPageInfo(docHandle, pageIndex)
        if (cancelledRenderIds.contains(renderId) || cancellationToken?.isCancelled() == true) {
            cancelTile(renderId)
            throw CancellationException("Render cancelled: renderId=$renderId")
        }
        val renderFinished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (cancellationToken != null) {
                    while (!renderFinished.get() && !cancellationToken.isCancelled()) {
                        delay(5)
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
            onRenderStarted?.invoke(renderId)
            if (renderDelayMs > 0) {
                delay(renderDelayMs)
            }
            val bmp = bitmapSupplier(tileWidth, tileHeight)
            if (cancelledRenderIds.contains(renderId) || cancellationToken?.isCancelled() == true) {
                cancelTile(renderId)
                throw CancellationException("Render cancelled: renderId=$renderId")
            }
            bmp
        } finally {
            renderFinished.set(true)
            watcher.cancel()
        }
    }

    override suspend fun getDocumentTitle(docHandle: Long): String? {
        return documentTitles[docHandle]
    }

    override suspend fun getOutline(docHandle: Long): List<OutlineItem> {
        check(isServiceConnected) { "Service is disconnected" }
        return documentOutlines[docHandle] ?: emptyList()
    }

    override suspend fun getPageText(docHandle: Long, pageIndex: Int): PageText {
        check(isServiceConnected) { "Service is disconnected" }
        return pageTexts[Pair(docHandle, pageIndex)] ?: PageText(pageIndex, "")
    }

    override suspend fun getPageLinks(docHandle: Long, pageIndex: Int): List<PageLink> {
        check(isServiceConnected) { "Service is disconnected" }
        return pageLinks[Pair(docHandle, pageIndex)] ?: emptyList()
    }

    override suspend fun getImageBounds(docHandle: Long, pageIndex: Int): List<PageRect> {
        check(isServiceConnected) { "Service is disconnected" }
        return pageImageBounds[Pair(docHandle, pageIndex)] ?: emptyList()
    }

    override suspend fun closeDocument(docHandle: Long) {
        documents.remove(docHandle)
        documentTitles.remove(docHandle)
        documentOutlines.remove(docHandle)
        pageTexts.keys.filter { it.first == docHandle }.forEach { pageTexts.remove(it) }
        pageLinks.keys.filter { it.first == docHandle }.forEach { pageLinks.remove(it) }
        pageImageBounds.keys.filter { it.first == docHandle }.forEach { pageImageBounds.remove(it) }
    }
}

