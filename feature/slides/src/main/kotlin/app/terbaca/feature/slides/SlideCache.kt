package app.terbaca.feature.slides

import android.graphics.Bitmap
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PerfTrace
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Damping ratio < 1.0f produces slight natural overshoot when snapping per PRD F-MODE-3.
 */
const val SLIDE_SNAP_DAMPING_RATIO = 0.8f

/**
 * Pages composed on each side of the visible slide (F-DECK-4: neighbours pre-rendered).
 */
const val SLIDE_BEYOND_VIEWPORT_PAGES = 1

/**
 * Cache capacity: the composed window (current + neighbours) plus one page entering during a swipe.
 */
const val SLIDE_CACHE_ENTRIES = 2 * SLIDE_BEYOND_VIEWPORT_PAGES + 2

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
 * A rendered slide is only reusable at the pixel size it was rendered for, so a resize or rotation
 * misses the cache and renders a sharp bitmap instead of upscaling the old one (PRD §7 slide flip).
 */
data class SlideKey(val pageIndex: Int, val widthPx: Int, val heightPx: Int)

/**
 * Small LRU cache for rendered slides (F-DECK-4, PRD §5).
 *
 * Evicted values are only dropped, never disposed: a pager page that is still composed may keep
 * drawing a bitmap the cache has already evicted, so recycling here would crash or blank it.
 * The GC reclaims bitmap memory once nothing references it.
 */
class SlideCache<K : Any, V : Any>(private val maxEntries: Int = SLIDE_CACHE_ENTRIES) {
    private val cache = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<K, V>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? = cache[key]

    @Synchronized
    fun put(key: K, value: V) {
        cache[key] = value
    }

    @Synchronized
    fun clear() = cache.clear()

    @Synchronized
    fun size(): Int = cache.size
}

/**
 * Slide bitmap cache for rendered slide pages.
 */
fun createSlideBitmapCache(maxEntries: Int = SLIDE_CACHE_ENTRIES): SlideCache<SlideKey, Bitmap> =
    SlideCache(maxEntries)

/**
 * Detects when a slide scroll settles on a new page and triggers haptic tick and page-change
 * callbacks (F-MODE-3). A drag that snaps back to the page it started on fires nothing.
 */
class SlideSettleDetector(
    initialPage: Int = 0,
    private val onPageSettled: (Int) -> Unit = {},
    private val onHapticTick: () -> Unit = {}
) {
    private var isScrolling = false
    private var lastSettledPage = initialPage

    fun onScrollStateChanged(scrolling: Boolean, settledPage: Int) {
        if (scrolling) {
            isScrolling = true
        } else if (isScrolling) {
            isScrolling = false
            if (settledPage != lastSettledPage) {
                lastSettledPage = settledPage
                onHapticTick()
                onPageSettled(settledPage)
            }
        }
    }
}

/**
 * Checks whether the slide bitmap for [page] at current viewport dimensions is already present
 * in [slideCache] and not recycled, emitting [PerfTrace.SLIDE_SHARP_ON_ARRIVAL] or
 * [PerfTrace.SLIDE_NOT_SHARP_ON_ARRIVAL] (F-DECK-4, PRD §7).
 *
 * @return True if sharp in cache on arrival, false otherwise.
 */
internal fun checkSlideSharpOnArrival(
    slideCache: SlideCache<SlideKey, *>,
    page: PageInfo?,
    viewportWidth: Int,
    viewportHeight: Int,
    onSharp: () -> Unit = { PerfTrace.mark(PerfTrace.SLIDE_SHARP_ON_ARRIVAL) },
    onNotSharp: () -> Unit = { PerfTrace.mark(PerfTrace.SLIDE_NOT_SHARP_ON_ARRIVAL) },
    isSharp: (Any) -> Boolean = { it is Bitmap && !it.isRecycled }
): Boolean {
    if (page == null || viewportWidth <= 0 || viewportHeight <= 0) return false
    val (targetW, targetH) = calculateAspectFit(
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        pageWidthPoints = page.widthPoints,
        pageHeightPoints = page.heightPoints
    )
    val key = SlideKey(page.index, targetW, targetH)
    val cached = slideCache.get(key)
    return if (cached != null && isSharp(cached)) {
        onSharp()
        true
    } else {
        onNotSharp()
        false
    }
}
