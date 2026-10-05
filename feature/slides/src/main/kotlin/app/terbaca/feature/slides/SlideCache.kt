package app.terbaca.feature.slides

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Damping ratio < 1.0f produces slight natural overshoot when snapping per PRD F-MODE-3.
 */
const val SLIDE_SNAP_DAMPING_RATIO = 0.8f

/**
 * Fits a page of [pageWidthPoints] x [pageHeightPoints] inside a viewport of
 * [viewportWidth] x [viewportHeight] device pixels while preserving aspect ratio (F-MODE-3, F-DECK-4).
 *
 * @return Pair of (targetWidthPx, targetHeightPx) at 1:1 screen resolution.
 */
fun calculateAspectFit(
    viewportWidth: Int,
    viewportHeight: Int,
    pageWidthPoints: Float,
    pageHeightPoints: Float
): Pair<Int, Int> {
    if (viewportWidth <= 0 || viewportHeight <= 0 || pageWidthPoints <= 0f || pageHeightPoints <= 0f) {
        return Pair(0, 0)
    }
    val scale = min(viewportWidth.toFloat() / pageWidthPoints, viewportHeight.toFloat() / pageHeightPoints)
    val width = (pageWidthPoints * scale).roundToInt().coerceAtLeast(1)
    val height = (pageHeightPoints * scale).roundToInt().coerceAtLeast(1)
    return Pair(width, height)
}

/**
 * Generic LRU cache that cleans up disposed items on eviction (F-DECK-4, PRD §5).
 */
class SlideCache<T : Any>(
    private val maxEntries: Int = 4,
    private val isDisposed: (T) -> Boolean = { false },
    private val disposer: (T) -> Unit = {}
) {
    private val cache = object : LinkedHashMap<Int, T>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, T>?): Boolean {
            if (size > maxEntries && eldest != null) {
                if (!isDisposed(eldest.value)) {
                    disposer(eldest.value)
                }
                return true
            }
            return false
        }
    }

    @Synchronized
    fun get(pageIndex: Int): T? {
        val item = cache[pageIndex] ?: return null
        if (isDisposed(item)) {
            cache.remove(pageIndex)
            return null
        }
        return item
    }

    @Synchronized
    fun put(pageIndex: Int, item: T) {
        if (isDisposed(item)) return
        val old = cache.put(pageIndex, item)
        if (old != null && old !== item && !isDisposed(old)) {
            disposer(old)
        }
    }

    @Synchronized
    fun clear() {
        for (item in cache.values) {
            if (!isDisposed(item)) {
                disposer(item)
            }
        }
        cache.clear()
    }

    @Synchronized
    fun size(): Int = cache.size
}

/**
 * Slide bitmap cache for rendered slide pages.
 */
fun createSlideBitmapCache(maxEntries: Int = 4): SlideCache<Bitmap> =
    SlideCache(
        maxEntries = maxEntries,
        isDisposed = { it.isRecycled },
        disposer = { it.recycle() }
    )

/**
 * Detects when a slide scroll settles and triggers haptic tick and page-change callbacks (F-MODE-3).
 */
class SlideSettleDetector(
    private val onPageSettled: (Int) -> Unit = {},
    private val onHapticTick: () -> Unit = {}
) {
    private var isScrolling = false

    fun onScrollStateChanged(scrolling: Boolean, settledPage: Int) {
        if (scrolling) {
            isScrolling = true
        } else if (isScrolling) {
            isScrolling = false
            onHapticTick()
            onPageSettled(settledPage)
        }
    }
}
