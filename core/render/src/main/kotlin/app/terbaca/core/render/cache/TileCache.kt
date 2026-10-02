package app.terbaca.core.render.cache

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.annotation.GuardedBy
import app.terbaca.core.model.TileKey

/**
 * Multi-tier in-memory tile cache with byte budgets and deterministic release per PRD §5.
 *
 * Tiers:
 * - Hot: tiles pinned by [pinHot] (visible tiles + prefetch ring). Never evicted by budget,
 *   because evicting visible content would only force an immediate re-render.
 * - Warm: recently visible tiles and base layers, LRU evicted by bytes over [TileMemoryBudget.warmBytes].
 * - Thumbnails: one bitmap per page, LRU evicted by bytes over [TileMemoryBudget.thumbnailBytes].
 *   Kept in a separate key space so a thumbnail can never be returned for a tile.
 *
 * Each entry's size is recorded when it is inserted, so byte counters stay exact even if a
 * bitmap is recycled elsewhere. Evicted bitmaps are released via [bitmapReleaser]
 * (default [Bitmap.recycle]) outside the lock; nothing relies on finalizers.
 */
class TileCache(
    val budget: TileMemoryBudget = TileMemoryBudget(),
    private val bitmapSizer: (Bitmap) -> Long = { it.allocationByteCount.toLong() },
    private val bitmapReleaser: (Bitmap) -> Unit = { if (!it.isRecycled) it.recycle() },
    private val isBitmapRecycled: (Bitmap) -> Boolean = { it.isRecycled }
) {
    private class Entry(val bitmap: Bitmap, val bytes: Long)

    /** A byte-counted map; access-ordered so iteration starts at the least recently used entry. */
    private class Tier<K> {
        val entries = LinkedHashMap<K, Entry>(16, 0.75f, true)
        var bytes = 0L

        fun put(key: K, entry: Entry): Entry? {
            val previous = entries.put(key, entry)
            bytes += entry.bytes - (previous?.bytes ?: 0L)
            return previous
        }

        fun remove(key: K): Entry? = entries.remove(key)?.also { bytes -= it.bytes }

        fun trimTo(maxBytes: Long, out: MutableList<Bitmap>) {
            val iterator = entries.values.iterator()
            while (bytes > maxBytes && iterator.hasNext()) {
                val entry = iterator.next()
                iterator.remove()
                bytes -= entry.bytes
                out += entry.bitmap
            }
        }

        fun clearInto(out: MutableList<Bitmap>) {
            entries.values.mapTo(out) { it.bitmap }
            entries.clear()
            bytes = 0L
        }
    }

    private val lock = Any()

    @GuardedBy("lock")
    private val pinnedKeys = HashSet<TileKey>()

    @GuardedBy("lock")
    private val hot = Tier<TileKey>()

    @GuardedBy("lock")
    private val warm = Tier<TileKey>()

    @GuardedBy("lock")
    private val thumbnails = Tier<Int>()

    val hotBytes: Long get() = synchronized(lock) { hot.bytes }
    val warmBytes: Long get() = synchronized(lock) { warm.bytes }
    val thumbnailBytes: Long get() = synchronized(lock) { thumbnails.bytes }
    val hotCount: Int get() = synchronized(lock) { hot.entries.size }
    val warmCount: Int get() = synchronized(lock) { warm.entries.size }
    val thumbnailCount: Int get() = synchronized(lock) { thumbnails.entries.size }

    /**
     * Makes [keys] the hot set. Cached tiles in [keys] move to hot; hot tiles not in [keys]
     * move to warm, which is then trimmed to budget.
     */
    fun pinHot(keys: Set<TileKey>) {
        val released = mutableListOf<Bitmap>()
        synchronized(lock) {
            pinnedKeys.clear()
            pinnedKeys += keys
            for (key in hot.entries.keys.filter { it !in keys }) {
                warm.put(key, hot.remove(key)!!)
            }
            for (key in keys) {
                warm.remove(key)?.let { hot.put(key, it) }
            }
            warm.trimTo(budget.warmBytes, released)
        }
        released.forEach(bitmapReleaser)
    }

    /** Returns the cached tile for [key], or `null` if absent or recycled. Refreshes LRU order. */
    fun get(key: TileKey): Bitmap? = synchronized(lock) {
        liveBitmap(hot, key) ?: liveBitmap(warm, key)
    }

    /** Stores [bitmap] for [key]: in hot if [key] is pinned, otherwise in warm (trimmed to budget). */
    fun put(key: TileKey, bitmap: Bitmap) {
        val released = mutableListOf<Bitmap>()
        synchronized(lock) {
            val entry = Entry(bitmap, bitmapSizer(bitmap))
            val (target, other) = if (key in pinnedKeys) hot to warm else warm to hot
            other.remove(key)?.let { if (it.bitmap !== bitmap) released += it.bitmap }
            target.put(key, entry)?.let { if (it.bitmap !== bitmap) released += it.bitmap }
            warm.trimTo(budget.warmBytes, released)
        }
        released.forEach(bitmapReleaser)
    }

    fun getThumbnail(pageIndex: Int): Bitmap? = synchronized(lock) { liveBitmap(thumbnails, pageIndex) }

    fun putThumbnail(pageIndex: Int, bitmap: Bitmap) {
        val released = mutableListOf<Bitmap>()
        synchronized(lock) {
            thumbnails.put(pageIndex, Entry(bitmap, bitmapSizer(bitmap)))
                ?.let { if (it.bitmap !== bitmap) released += it.bitmap }
            thumbnails.trimTo(budget.thumbnailBytes, released)
        }
        released.forEach(bitmapReleaser)
    }

    /** Removes and releases the tile for [key]. Returns `true` if it was cached. */
    fun remove(key: TileKey): Boolean {
        val entry = synchronized(lock) { hot.remove(key) ?: warm.remove(key) } ?: return false
        bitmapReleaser(entry.bitmap)
        return true
    }

    /**
     * Handles memory pressure per PRD §5:
     * - [ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW] and above: drop the warm tier.
     * - [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] and above (backgrounded): also drop
     *   thumbnails, keeping only visible (hot) content.
     */
    fun onTrimMemory(level: Int) {
        val released = mutableListOf<Bitmap>()
        synchronized(lock) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) warm.clearInto(released)
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) thumbnails.clearInto(released)
        }
        released.forEach(bitmapReleaser)
    }

    /** Releases every cached bitmap in all tiers. */
    fun clear() {
        val released = mutableListOf<Bitmap>()
        synchronized(lock) {
            hot.clearInto(released)
            warm.clearInto(released)
            thumbnails.clearInto(released)
            pinnedKeys.clear()
        }
        released.forEach(bitmapReleaser)
    }

    @GuardedBy("lock")
    private fun <K> liveBitmap(tier: Tier<K>, key: K): Bitmap? {
        val entry = tier.entries[key] ?: return null
        if (!isBitmapRecycled(entry.bitmap)) return entry.bitmap
        tier.remove(key)
        return null
    }

    companion object {
        /** Key for a page's fit-width base layer. Distinct from pyramid tiles and thumbnails. */
        fun baseLayerKey(pageIndex: Int) = TileKey(pageIndex = pageIndex, level = -2, col = 0, row = 0)
    }
}
