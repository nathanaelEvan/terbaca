package app.terbaca.core.render.cache

import android.graphics.Bitmap
import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.pyramid.TilePyramid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Thumbnail pipeline coordinating memory cache, disk cache, and render dispatch per PRD §4.4, §4.5, §5.
 *
 * Tiers:
 * 1. Memory: [TileCache] thumbnail tier (~20 MB budget, LRU). It only ever holds one document's
 *    thumbnails at one size; asking for another document or size clears it.
 * 2. Disk: [ThumbnailDiskCache] WebP files (~200 MB budget, LRU).
 * 3. Render: [PageRenderer] rendering at ~400 px width via [TilePyramid.computeThumbnailBounds].
 *
 * Concurrent requests for the same thumbnail key are coalesced. The shared render runs in a
 * pipeline-owned scope, so one caller being cancelled doesn't cancel it for the others; it is
 * cancelled only once every caller waiting for it has gone.
 *
 * The default [memoryCache] doesn't recycle evicted bitmaps, because thumbnails handed out by
 * [getThumbnail] may still be drawn after they leave the cache. Call [onTrimMemory] from
 * `ComponentCallbacks2.onTrimMemory`.
 */
class ThumbnailPipeline(
    val memoryCache: TileCache = TileCache(bitmapReleaser = {}),
    val diskCache: ThumbnailDiskCache,
    val renderer: PageRenderer,
    renderContext: CoroutineContext = Dispatchers.Default
) {
    private class InFlight(val deferred: Deferred<Bitmap?>) {
        var waiters = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + renderContext)

    private val inFlightLock = Any()

    @GuardedBy("inFlightLock")
    private val inFlight = HashMap<String, InFlight>()

    /** Callers currently waiting on a shared render, across all keys. */
    @get:VisibleForTesting
    internal val inFlightWaiters: Int
        get() = synchronized(inFlightLock) { inFlight.values.sumOf { it.waiters } }

    private val memoryLock = Any()

    /** Which document and size the memory tier currently holds, as `"{contentHash}_{sizePx}"`. */
    @GuardedBy("memoryLock")
    private var memoryOwner: String? = null

    /**
     * Retrieves or generates a thumbnail for [pageInfo].
     * Checks memory cache first, then disk cache, and finally dispatches rendering.
     *
     * Returns `null` if the thumbnail can't be produced (renderer or disk failure). Rethrows
     * [CancellationException] only when the calling coroutine itself was cancelled.
     */
    @Suppress("TooGenericExceptionCaught") // A thumbnail failure must never crash the caller's UI.
    suspend fun getThumbnail(
        docHandle: Long,
        contentHash: String,
        pageInfo: PageInfo,
        targetWidthPx: Int = ThumbnailDiskCache.DEFAULT_THUMBNAIL_WIDTH_PX
    ): Bitmap? = try {
        loadThumbnail(docHandle, contentHash, pageInfo, targetWidthPx)
    } catch (e: CancellationException) {
        // Our own cancellation propagates; a render cancelled under us just yields no thumbnail.
        currentCoroutineContext().ensureActive()
        null
    } catch (e: Exception) {
        null
    }

    private suspend fun loadThumbnail(
        docHandle: Long,
        contentHash: String,
        pageInfo: PageInfo,
        targetWidthPx: Int
    ): Bitmap? {
        val owner = "${contentHash}_$targetWidthPx"
        val memoryHit = synchronized(memoryLock) {
            if (memoryOwner != null && memoryOwner != owner) memoryCache.clearThumbnails()
            memoryOwner = owner
            memoryCache.getThumbnail(pageInfo.index)
        }
        if (memoryHit != null) return memoryHit

        val diskHit = withContext(Dispatchers.IO) {
            diskCache.get(contentHash, pageInfo.index, targetWidthPx)
        }
        if (diskHit != null) {
            putInMemory(owner, pageInfo.index, diskHit)
            return diskHit
        }

        val key = diskCache.key(contentHash, pageInfo.index, targetWidthPx)
        return coalesceRender(key) {
            // A request that finished between our memory check and taking this slot has already
            // cached the thumbnail; don't render it twice.
            getFromMemory(owner, pageInfo.index)?.let { return@coalesceRender it }
            val bounds = TilePyramid.computeThumbnailBounds(pageInfo, targetWidthPx)
            val rendered = renderer.renderTile(
                docHandle = docHandle,
                pageIndex = pageInfo.index,
                fullWidth = bounds.fullWidth,
                fullHeight = bounds.fullHeight,
                startX = bounds.startX,
                startY = bounds.startY,
                tileWidth = bounds.pixelWidth,
                tileHeight = bounds.pixelHeight
            )
            withContext(Dispatchers.IO) {
                diskCache.put(contentHash, pageInfo.index, targetWidthPx, rendered)
            }
            putInMemory(owner, pageInfo.index, rendered)
            rendered
        }
    }

    /**
     * Loads a thumbnail directly from disk cache without requiring an active document handle.
     * Ideal for library/recents display on cold startup (PRD §7: no render service spin-up).
     */
    suspend fun loadFromDisk(
        contentHash: String,
        pageIndex: Int,
        targetWidthPx: Int = ThumbnailDiskCache.DEFAULT_THUMBNAIL_WIDTH_PX
    ): Bitmap? = withContext(Dispatchers.IO) {
        diskCache.get(contentHash, pageIndex, targetWidthPx)
    }

    /** Drops in-memory thumbnails under memory pressure per PRD §5 (see [TileCache.onTrimMemory]). */
    fun onTrimMemory(level: Int) = memoryCache.onTrimMemory(level)

    private fun getFromMemory(owner: String, pageIndex: Int): Bitmap? = synchronized(memoryLock) {
        if (memoryOwner == owner) memoryCache.getThumbnail(pageIndex) else null
    }

    /** Caches [bitmap] only if the memory tier still belongs to [owner], so a late render can't leak across documents. */
    private fun putInMemory(owner: String, pageIndex: Int, bitmap: Bitmap) = synchronized(memoryLock) {
        if (memoryOwner == owner) memoryCache.putThumbnail(pageIndex, bitmap)
    }

    private suspend fun coalesceRender(key: String, block: suspend () -> Bitmap?): Bitmap? {
        val entry = synchronized(inFlightLock) {
            inFlight.getOrPut(key) {
                // Lazy, so the completion handler is registered before the render can finish.
                val created = InFlight(scope.async(start = CoroutineStart.LAZY) { block() })
                created.deferred.invokeOnCompletion {
                    synchronized(inFlightLock) {
                        if (inFlight[key] === created) inFlight.remove(key)
                    }
                }
                created
            }.also { it.waiters++ }
        }
        entry.deferred.start()
        try {
            return entry.deferred.await()
        } finally {
            synchronized(inFlightLock) {
                entry.waiters--
                if (entry.waiters == 0 && !entry.deferred.isCompleted) {
                    // Nobody wants this thumbnail any more (e.g. scrolled away); stop rendering it.
                    if (inFlight[key] === entry) inFlight.remove(key)
                    entry.deferred.cancel()
                }
            }
        }
    }
}
