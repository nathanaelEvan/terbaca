package app.terbaca.core.render.cache

import android.graphics.Bitmap
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.pyramid.TilePyramid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thumbnail pipeline coordinating memory cache, disk cache, and render dispatch per PRD §4.4, §4.5, §5.
 *
 * Tiers:
 * 1. Memory: [TileCache] thumbnail tier (~20 MB budget, LRU).
 * 2. Disk: [ThumbnailDiskCache] WebP files (~200 MB budget, LRU).
 * 3. Render: [PageRenderer] rendering at ~400 px width via [TilePyramid.computeThumbnailBounds].
 *
 * Concurrent requests for the same thumbnail key are coalesced to avoid duplicate renders.
 */
class ThumbnailPipeline(
    val memoryCache: TileCache,
    val diskCache: ThumbnailDiskCache,
    val renderer: PageRenderer
) {
    private val inFlightLock = Mutex()
    private val inFlight = HashMap<String, Deferred<Bitmap?>>()
    private val hashLock = Any()
    private var currentContentHash: String? = null

    /**
     * Retrieves or generates a thumbnail for [pageInfo].
     * Checks memory cache first, then disk cache, and finally dispatches rendering.
     */
    suspend fun getThumbnail(
        docHandle: Long,
        contentHash: String,
        pageInfo: PageInfo,
        targetWidthPx: Int = ThumbnailDiskCache.DEFAULT_THUMBNAIL_WIDTH_PX
    ): Bitmap? {
        synchronized(hashLock) {
            if (currentContentHash != null && currentContentHash != contentHash) {
                memoryCache.clearThumbnails()
            }
            currentContentHash = contentHash
        }
        val memoryHit = memoryCache.getThumbnail(pageInfo.index)
        if (memoryHit != null) return memoryHit

        val diskHit = withContext(Dispatchers.IO) {
            diskCache.get(contentHash, pageInfo.index, targetWidthPx)
        }
        if (diskHit != null) {
            memoryCache.putThumbnail(pageInfo.index, diskHit)
            return diskHit
        }

        val key = diskCache.key(contentHash, pageInfo.index, targetWidthPx)
        return coalesceRender(key) {
            // A request that finished between our memory check and taking this slot has already
            // cached the thumbnail; don't render it twice.
            memoryCache.getThumbnail(pageInfo.index)?.let { return@coalesceRender it }
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
            memoryCache.putThumbnail(pageInfo.index, rendered)
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

    private suspend fun coalesceRender(key: String, block: suspend () -> Bitmap?): Bitmap? {
        var deferredToRun: CompletableDeferred<Bitmap?>? = null
        val deferred = inFlightLock.withLock {
            val existing = inFlight[key]
            if (existing != null) {
                existing
            } else {
                val newDeferred = CompletableDeferred<Bitmap?>()
                inFlight[key] = newDeferred
                deferredToRun = newDeferred
                newDeferred
            }
        }

        if (deferredToRun != null) {
            try {
                val result = block()
                deferredToRun.complete(result)
            } catch (t: Throwable) {
                deferredToRun.completeExceptionally(t)
                throw t
            } finally {
                inFlightLock.withLock {
                    inFlight.remove(key)
                }
            }
        }

        return deferred.await()
    }
}
