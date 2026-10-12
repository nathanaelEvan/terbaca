package app.terbaca.feature.reader

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.terbaca.core.model.PageInfo
import app.terbaca.core.model.TileKey
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.PerfTrace
import app.terbaca.core.render.cache.TileCache
import app.terbaca.core.render.cache.TileMemoryBudget
import app.terbaca.core.render.pyramid.PyramidLevel
import app.terbaca.core.render.pyramid.TileBounds
import app.terbaca.core.render.pyramid.TilePyramid
import app.terbaca.core.render.pyramid.ViewportRect
import app.terbaca.core.render.scheduler.RenderScheduler
import app.terbaca.core.render.scheduler.TileLayerType
import app.terbaca.core.render.scheduler.TilePriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val MAX_TILES_SWAPPED_PER_FRAME = 2
const val IDLE_SWAP_BUDGET = Int.MAX_VALUE

/**
 * Returns the maximum number of tiles to swap into the cache this frame (M7-02).
 * Capped at [MAX_TILES_SWAPPED_PER_FRAME] while touching or flinging to protect scroll/gesture
 * frame rate at 120 Hz; uncapped ([IDLE_SWAP_BUDGET]) when idle so arrived tiles enter cache in
 * one frame.
 */
internal fun swapBudget(isGesturing: Boolean, isFlinging: Boolean): Int {
    return 2 // NEGATIVE CONTROL
}

const val TILE_CROSSFADE_DURATION_MS = 100L // F-REN-3: 100-150 ms (M6-06)

/** Longest a loaded tile waits for the rest of the visible set before it is revealed anyway. */
const val TILE_REVEAL_TIMEOUT_MS = 300L

/**
 * The same wait when every visible page already shows its base layer: blurry for a moment then one
 * sharp fade beats sharp squares appearing over blur. A heavy zoomed screen is ~45 tiles (~0.5 s).
 */
const val TILE_REVEAL_TIMEOUT_OVER_BASE_MS = 1500L

/** Pages above and below the viewport whose base layers are kept ready for scrolling onto. */
private const val ADJACENT_BASE_PAGES = 2

/**
 * Scroll per viewport update, as a fraction of the viewport height, above which only base layers are
 * requested (~8000 px/s at 120 Hz on the Pad 7): detail tiles of a page flying past are stale before
 * they arrive and would delay the base layers of the pages coming next.
 */
private const val FAST_SCROLL_FRACTION = 0.03f
private const val FAST_SCROLL_SETTLE_MS = 100L

/** Fade-start marker for a tile that is loaded but not revealed yet (draws at alpha 0). */
const val TILE_UNREVEALED = Long.MAX_VALUE
private const val NO_PENDING_REVEAL = -1L
private const val RENDER_SCHEDULER_CONCURRENCY = 2

/** Drawn where a page has no content yet, so a fast fling shows page shapes, not a void. */
private val PAGE_PLACEHOLDER_COLOR = Color.White

private class IntSizeRef(var width: Int = 0, var height: Int = 0)

@Composable
fun DocumentSurface(
    modifier: Modifier = Modifier,
    state: DocumentTransformState,
    renderer: PageRenderer? = null,
    docHandle: Long = 0L,
    onSurfaceDrawn: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val decay = remember(density) { splineBasedDecay<Float>(density) }

    val view = LocalView.current
    SetupHighRefreshRate(view)

    val context = view.context
    val evictedPrevFrame = remember { ArrayList<Bitmap>() }
    val evictedThisFrame = remember { ArrayList<Bitmap>() }
    val tileCache = remember {
        TileCache(
            budget = TileMemoryBudget.fromContext(context),
            bitmapReleaser = { evictedThisFrame.add(it) }
        )
    }
    val tileFadeStartTimes = remember { HashMap<TileKey, Long>() }
    val pendingTileArrivals = remember { ArrayDeque<Pair<TileKey, Bitmap>>() }
    val inFlightTiles = remember { HashSet<TileKey>() }

    val tileArrivals = remember { mutableIntStateOf(0) }
    val frameClock = remember { mutableLongStateOf(0L) }
    val reloadTrigger = remember { mutableIntStateOf(0) }

    val scheduler = remember(renderer, docHandle) {
        createRenderScheduler(coroutineScope, renderer, docHandle)
    }

    SetupLifecycleEffects(
        renderer = renderer,
        reloadTrigger = reloadTrigger,
        context = context,
        tileCache = tileCache,
        pendingTileArrivals = pendingTileArrivals,
        evictedPrevFrame = evictedPrevFrame,
        evictedThisFrame = evictedThisFrame,
        scheduler = scheduler
    )

    val lastSize = remember { IntSizeRef() }
    // Per page: the last level that fully covered its visible part (kept under the next level).
    val underlayLevels = remember { HashMap<Int, PyramidLevel>() }
    val pyramidLevelCache = remember(docHandle, state.layout) { HashMap<Int, List<PyramidLevel>>() }
    val revealPendingSince = remember { LongArray(1) { NO_PENDING_REVEAL } }
    val viewportGeneration = remember { AtomicLong(0L) }

    val sizeModifier = Modifier.onSizeChanged { size ->
        handleSizeChanged(
            size = size,
            lastSize = lastSize,
            tileCache = tileCache,
            tileFadeStartTimes = tileFadeStartTimes,
            pendingTileArrivals = pendingTileArrivals,
            scheduler = scheduler,
            underlayLevels = underlayLevels,
            state = state,
            inFlightTiles = inFlightTiles,
            viewportGeneration = viewportGeneration,
            pyramidLevelCache = pyramidLevelCache
        )
    }
    val gestureModifier = Modifier.documentGestures(state, coroutineScope, decay) {
        // Gesture over: time the sharpening (PRD §7), and draw a frame in case it's sharp already.
        // Only while tracing, so a release build doesn't draw an extra frame per gesture.
        if (PerfTrace.beginSharpen()) tileArrivals.intValue++
    }
    DisposableEffect(Unit) {
        // Leaving the reader mid-section: don't let the next document's first sharp frame close it.
        onDispose {
            PerfTrace.cancelSharpen()
            PerfTrace.abandonOpen()
        }
    }

    if (scheduler != null) {
        DocumentTileCollector(
            state = state,
            requester = remember(scheduler) {
                TileRequester(
                    scheduler, coroutineScope, tileCache, inFlightTiles,
                    pendingTileArrivals, tileArrivals, viewportGeneration
                )
            },
            tileCache = tileCache,
            tileFadeStartTimes = tileFadeStartTimes,
            underlayLevels = underlayLevels,
            reloadTrigger = reloadTrigger,
            pyramidLevelCache = pyramidLevelCache
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(sizeModifier)
            .then(gestureModifier)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = state.scale
            val offsetX = state.offsetX
            val offsetY = state.offsetY
            tileArrivals.intValue
            frameClock.longValue
            val now = System.currentTimeMillis()

            drainEvictedBitmaps(evictedPrevFrame, evictedThisFrame)
            val budget = swapBudget(isGesturing = state.isGesturing, isFlinging = state.isFlinging)
            swapPendingTiles(pendingTileArrivals, tileCache, tileFadeStartTimes, maxSwaps = budget) { key ->
                // A tile stays "in flight" until it is in the cache; releasing it at arrival let
                // every frame between arrival and swap request it again.
                inFlightTiles.remove(key)
            }

            var hasActiveFade = false
            if (state.viewportWidth > 0f && state.viewportHeight > 0f) {
                val layout = state.layout
                val visibleRect = state.getVisibleRectPoints()
                val pages = layout.pagesIn(visibleRect).mapNotNull { index ->
                    val pageVisible = layout.visibleRectOnPage(index, visibleRect) ?: return@mapNotNull null
                    val page = layout.pages[index]
                    val levels = pyramidLevelCache.getOrPut(index) {
                        TilePyramid.buildLevels(page, state.viewportWidth, state.viewportHeight)
                    }
                    val level = TilePyramid.selectLevel(levels, state.levelScale)
                    PageFrame(index, pageVisible, level, TilePyramid.getVisibleTiles(page, level, pageVisible))
                }
                // One reveal batch across every visible page, so the whole screen sharpens at once.
                val basesShown = pages.all { tileCache.get(TileCache.baseLayerKey(it.index)) != null }
                revealPendingSince[0] = revealTiles(
                    pages.flatMap { it.tiles },
                    { tileCache.get(it) != null },
                    tileFadeStartTimes,
                    revealPendingSince[0].takeIf { it != NO_PENDING_REVEAL },
                    now,
                    timeoutMs = if (basesShown) TILE_REVEAL_TIMEOUT_OVER_BASE_MS else TILE_REVEAL_TIMEOUT_MS
                ) ?: NO_PENDING_REVEAL
                // The crossfade keeps frames coming until the layer is opaque, so the end fires on that frame.
                if (PerfTrace.isSharpenPending || PerfTrace.isOpenPending) {
                    val sharp = isViewSharp(pages.map { it.tiles }, { tileCache.get(it) != null }, tileFadeStartTimes, now)
                    if (sharp) {
                        PerfTrace.endSharpen()
                        PerfTrace.endOpen()
                    }
                }
                for (frame in pages) {
                    val pageRect = layout.pageRect(frame.index)
                    hasActiveFade = drawTilePyramid(
                        page = layout.pages[frame.index],
                        frame = frame,
                        tileCache = tileCache,
                        tileFadeStartTimes = tileFadeStartTimes,
                        underlayLevels = underlayLevels,
                        now = now,
                        scale = scale,
                        offsetX = offsetX + pageRect.left * scale,
                        offsetY = offsetY + pageRect.top * scale
                    ) || hasActiveFade
                }
            }
            // Keep drawing while a batch waits, so its timeout fires without a new arrival.
            if (revealPendingSince[0] != NO_PENDING_REVEAL) hasActiveFade = true

            if (hasActiveFade || pendingTileArrivals.isNotEmpty()) {
                frameClock.longValue = now
            }
            onSurfaceDrawn?.invoke()
        }
    }
}

/** The part of page [index] inside document-point rect [visible], in page points; null if none. */
internal fun DocumentLayout.visibleRectOnPage(index: Int, visible: ViewportRect): ViewportRect? {
    val page = pageRect(index)
    val left = max(visible.left, page.left)
    val top = max(visible.top, page.top)
    val right = min(visible.right, page.right)
    val bottom = min(visible.bottom, page.bottom)
    if (left >= right || top >= bottom) return null
    return ViewportRect(left - page.left, top - page.top, right - page.left, bottom - page.top)
}

private fun createRenderScheduler(
    scope: CoroutineScope,
    renderer: PageRenderer?,
    docHandle: Long
): RenderScheduler<Bitmap>? {
    if (renderer == null || docHandle == 0L) return null
    return RenderScheduler(
        scope = scope,
        maxConcurrentRenders = RENDER_SCHEDULER_CONCURRENCY
    ) { bounds, token ->
        renderer.renderTile(
            renderId = System.nanoTime(),
            docHandle = docHandle,
            pageIndex = bounds.key.pageIndex,
            fullWidth = bounds.fullWidth,
            fullHeight = bounds.fullHeight,
            startX = bounds.startX,
            startY = bounds.startY,
            tileWidth = bounds.pixelWidth,
            tileHeight = bounds.pixelHeight,
            cancellationToken = token
        )
    }
}

@Suppress("LongParameterList")
private fun handleSizeChanged(
    size: IntSize,
    lastSize: IntSizeRef,
    tileCache: TileCache,
    tileFadeStartTimes: HashMap<TileKey, Long>,
    pendingTileArrivals: ArrayDeque<Pair<TileKey, Bitmap>>,
    scheduler: RenderScheduler<Bitmap>?,
    underlayLevels: HashMap<Int, PyramidLevel>,
    state: DocumentTransformState,
    inFlightTiles: HashSet<TileKey>,
    viewportGeneration: AtomicLong,
    pyramidLevelCache: HashMap<Int, List<PyramidLevel>>
) {
    if (size.width > 0 && size.height > 0) {
        if (lastSize.width != 0 && lastSize.height != 0 &&
            (lastSize.width != size.width || lastSize.height != size.height)
        ) {
            viewportGeneration.incrementAndGet()
            tileCache.clear()
            tileFadeStartTimes.clear()
            pendingTileArrivals.forEach { (_, bmp) -> if (!bmp.isRecycled) bmp.recycle() }
            pendingTileArrivals.clear()
            scheduler?.cancelAll()
            inFlightTiles.clear()
            underlayLevels.clear()
            pyramidLevelCache.clear()
        }
        lastSize.width = size.width
        lastSize.height = size.height
        state.updateViewport(size.width.toFloat(), size.height.toFloat())
    }
}

@Composable
private fun SetupHighRefreshRate(view: android.view.View) {
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val window = (view.context as? Activity)?.window
            window?.let { win ->
                val display = view.display
                val modes = display?.supportedModes
                val maxRateMode = modes?.maxByOrNull { it.refreshRate }
                if (maxRateMode != null && maxRateMode.refreshRate > 60f) {
                    val attrs = win.attributes
                    attrs.preferredDisplayModeId = maxRateMode.modeId
                    win.attributes = attrs
                }
            }
        }
        onDispose {}
    }
}

@Composable
@Suppress("LongParameterList")
private fun SetupLifecycleEffects(
    renderer: PageRenderer?,
    reloadTrigger: MutableIntState,
    context: android.content.Context,
    tileCache: TileCache,
    pendingTileArrivals: ArrayDeque<Pair<TileKey, Bitmap>>,
    evictedPrevFrame: ArrayList<Bitmap>,
    evictedThisFrame: ArrayList<Bitmap>,
    scheduler: RenderScheduler<Bitmap>?
) {
    DisposableEffect(renderer) {
        if (renderer == null) return@DisposableEffect onDispose {}
        val unregister = renderer.registerRecoveryListener {
            reloadTrigger.intValue++
        }
        onDispose { unregister() }
    }

    DisposableEffect(tileCache) {
        val appContext = context.applicationContext
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = tileCache.onTrimMemory(level)
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = tileCache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }
        appContext.registerComponentCallbacks(callbacks)
        onDispose {
            appContext.unregisterComponentCallbacks(callbacks)
            pendingTileArrivals.forEach { (_, bmp) -> bmp.recycle() }
            pendingTileArrivals.clear()
            tileCache.clear()
            evictedPrevFrame.forEach { if (!it.isRecycled) it.recycle() }
            evictedPrevFrame.clear()
            evictedThisFrame.forEach { if (!it.isRecycled) it.recycle() }
            evictedThisFrame.clear()
        }
    }

    DisposableEffect(scheduler) {
        onDispose { scheduler?.cancelAll() }
    }
}

private fun Modifier.documentGestures(
    state: DocumentTransformState,
    coroutineScope: CoroutineScope,
    decay: DecayAnimationSpec<Float>,
    onMotionEnd: () -> Unit
): Modifier = pointerInput(state) {
    val velocityTracker = PanVelocityTracker()
    var flingJob: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        PerfTrace.cancelSharpen()
        flingJob?.cancel()
        flingJob = null
        state.isGesturing = true
        velocityTracker.reset(down.uptimeMillis)

        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        try {
            do {
                val event = awaitPointerEvent()
                val canceled = event.changes.any { it.isConsumed }
                if (!canceled) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    val centroid = event.calculateCentroid()

                    if (!pastTouchSlop) {
                        zoom *= zoomChange
                        pan += panChange
                        if (pan.getDistance() > touchSlop || zoom < 0.95f || zoom > 1.05f) {
                            pastTouchSlop = true
                        }
                    }

                    if (pastTouchSlop) {
                        if (centroid != Offset.Unspecified) {
                            if (zoomChange != 1f) {
                                state.beginZoom()
                                state.onPinch(zoomChange, centroid, panChange)
                            } else if (panChange != Offset.Zero) {
                                state.onPan(panChange.x, panChange.y)
                            }
                        }
                        event.changes.forEach { change: PointerInputChange ->
                            if (change.position != change.previousPosition) change.consume()
                        }
                        velocityTracker.add(
                            timeMillis = event.changes.maxOf { it.uptimeMillis },
                            pan = panChange,
                            pressedPointers = event.changes.count { it.pressed }
                        )
                    }
                }
            } while (event.changes.any { it.pressed })
        } finally {
            // Also on cancellation, or the level would stay frozen.
            state.endZoom()
            state.isGesturing = false
        }

        if (pastTouchSlop) {
            val velocity = velocityTracker.velocity()
            flingJob = coroutineScope.launch {
                state.fling(velocity.x, velocity.y, decay)
                // Not reached when a new touch or a jump cancels the fling.
                onMotionEnd()
            }
        }
    }
}

/**
 * Requests tiles and base layers, coalescing on [inFlightTiles] (main thread only, like every
 * caller), and queues arrivals for [swapPendingTiles]. Arrivals from before a viewport reset
 * ([viewportGeneration]) are dropped.
 */
private class TileRequester(
    val scheduler: RenderScheduler<Bitmap>,
    private val coroutineScope: CoroutineScope,
    private val tileCache: TileCache,
    private val inFlightTiles: HashSet<TileKey>,
    private val pendingTileArrivals: ArrayDeque<Pair<TileKey, Bitmap>>,
    private val tileArrivals: MutableIntState,
    private val viewportGeneration: AtomicLong
) {
    fun request(bounds: TileBounds, priority: TilePriority) {
        if (bounds.pixelWidth <= 0 || bounds.pixelHeight <= 0) return
        if (tileCache.get(bounds.key) != null || !inFlightTiles.add(bounds.key)) return
        val currentGen = viewportGeneration.get()
        val deferred = scheduler.enqueue(bounds, priority)
        coroutineScope.launch {
            var bmp: Bitmap? = null
            var queued = false
            try {
                bmp = deferred.await()
                if (viewportGeneration.get() != currentGen) {
                    if (!bmp.isRecycled) bmp.recycle()
                    return@launch
                }
                PerfTrace.mark(PerfTrace.TILE_ARRIVED)
                pendingTileArrivals.add(bounds.key to bmp)
                queued = true // released by swapPendingTiles
                tileArrivals.intValue++
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    bmp?.let { if (!it.isRecycled) it.recycle() }
                }
                // Retried on next viewport update
            } finally {
                if (!queued) inFlightTiles.remove(bounds.key)
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun DocumentTileCollector(
    state: DocumentTransformState,
    requester: TileRequester,
    tileCache: TileCache,
    tileFadeStartTimes: HashMap<TileKey, Long>,
    underlayLevels: HashMap<Int, PyramidLevel>,
    reloadTrigger: MutableIntState,
    pyramidLevelCache: HashMap<Int, List<PyramidLevel>>
) {
    LaunchedEffect(state, requester) {
        val layout = state.layout
        var lastOffsetX = state.offsetX
        var lastOffsetY = state.offsetY

        // The viewport size is part of the key: a resize clears the tile cache, and when it leaves
        // scale and offsets unchanged nothing was requested until the next touch (blank page).
        snapshotFlow {
            SurfaceViewportSnapshot(
                state.scale, state.offsetX, state.offsetY, reloadTrigger.intValue, state.isZooming,
                state.viewportWidth, state.viewportHeight
            )
        }.collectLatest { (scale, offsetX, offsetY, _, zooming) ->
            if (state.viewportWidth <= 0f || state.viewportHeight <= 0f) return@collectLatest
            // Content moves opposite to the viewport: offset decreasing = viewing further right/down.
            val scrollDeltaX = lastOffsetX - offsetX
            val scrollDeltaY = lastOffsetY - offsetY
            lastOffsetX = offsetX
            lastOffsetY = offsetY

            val visibleRect = state.getVisibleRectPoints()
            val visiblePages = layout.pagesIn(visibleRect)
            // Forget underlays of pages scrolled well away: coming back, a stale level would be
            // drawn and pinned under the current one for nothing.
            if (!visiblePages.isEmpty()) {
                val kept = visiblePages.first - ADJACENT_BASE_PAGES..visiblePages.last + ADJACENT_BASE_PAGES
                underlayLevels.keys.retainAll { it in kept }
            }

            val plan = computeTilePlan(
                layout = layout,
                visibleRect = visibleRect,
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY,
                fitWidthScale = state.fitWidthScale,
                levelScale = state.levelScale,
                viewportWidth = state.viewportWidth,
                viewportHeight = state.viewportHeight,
                scrollDeltaX = scrollDeltaX,
                scrollDeltaY = scrollDeltaY,
                zooming = zooming,
                underlayLevels = underlayLevels,
                pyramidLevelCache = pyramidLevelCache
            )

            tileCache.pinHot(plan.activeKeys)
            requester.scheduler.cancelStaleExcept(plan.cancelStaleKeepKeys)

            tileFadeStartTimes.keys.retainAll { tileCache.get(it) != null }

            plan.visibleBases.forEach { requester.request(it, TilePriority(TileLayerType.VISIBLE_BASE)) }
            if (!zooming && hypot(scrollDeltaX, scrollDeltaY) > state.viewportHeight * FAST_SCROLL_FRACTION) {
                plan.adjacentBases.forEach { requester.request(it, TilePriority(TileLayerType.ADJACENT_BASE)) }
                // Cancelled by the next update (collectLatest); if none comes, the view stopped here.
                delay(FAST_SCROLL_SETTLE_MS)
            }
            plan.visibleDetail.forEach { (tile, dist) ->
                requester.request(tile, TilePriority(TileLayerType.VISIBLE_DETAIL, distanceToCenter = dist))
            }
            plan.prefetch.forEach { (tile, dist) ->
                requester.request(tile, TilePriority(TileLayerType.PREFETCH_DETAIL, distanceToCenter = dist))
            }
            plan.adjacentBases.forEach { requester.request(it, TilePriority(TileLayerType.ADJACENT_BASE)) }
        }
    }
}

internal data class TileCollectionPlan(
    val visibleBases: List<TileBounds>,
    val adjacentBases: List<TileBounds>,
    val visibleDetail: List<Pair<TileBounds, Float>>,
    val prefetch: List<Pair<TileBounds, Float>>,
    val activeKeys: Set<TileKey>,
    val cancelStaleKeepKeys: Set<TileKey>
)

@Suppress("LongParameterList")
internal fun computeTilePlan(
    layout: DocumentLayout,
    visibleRect: ViewportRect,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    fitWidthScale: Float,
    levelScale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    scrollDeltaX: Float = 0f,
    scrollDeltaY: Float = 0f,
    zooming: Boolean,
    underlayLevels: Map<Int, PyramidLevel> = emptyMap(),
    pyramidLevelCache: MutableMap<Int, List<PyramidLevel>>? = null
): TileCollectionPlan {
    val visiblePages = layout.pagesIn(visibleRect)
    val viewportCenter = Offset(viewportWidth / 2f, viewportHeight / 2f)

    val activeKeys = HashSet<TileKey>()
    val visibleBases = ArrayList<TileBounds>()
    val visibleDetail = ArrayList<Pair<TileBounds, Float>>()
    val prefetch = ArrayList<Pair<TileBounds, Float>>()

    for (index in visiblePages) {
        val pageVisible = layout.visibleRectOnPage(index, visibleRect) ?: continue
        val page = layout.pages[index]
        val pageRect = layout.pageRect(index)
        val origin = Offset(offsetX + pageRect.left * scale, offsetY + pageRect.top * scale)
        fun distance(tile: TileBounds) = (
            origin + Offset(tile.pageRect.centerX * scale, tile.pageRect.centerY * scale) - viewportCenter
        ).getDistance()

        visibleBases += TilePyramid.computeBaseLevelBounds(page, fitWidthScale)

        if (!zooming) {
            val levels = pyramidLevelCache?.getOrPut(index) {
                TilePyramid.buildLevels(page, viewportWidth, viewportHeight)
            } ?: TilePyramid.buildLevels(page, viewportWidth, viewportHeight)
            val currentLevel = TilePyramid.selectLevel(levels, levelScale)
            val tiles = TilePyramid.getVisibleTiles(page, currentLevel, pageVisible)
            tiles.mapTo(visibleDetail) { it to distance(it) }
            // PRD §4.5: prefetch the ring of tiles just off-screen in the scroll direction (all
            // around when still), so panning reveals sharp tiles instead of the blurry base.
            TilePyramid.getPrefetchRing(page, currentLevel, tiles, scrollDeltaX, scrollDeltaY)
                .mapTo(prefetch) { it to distance(it) }
        }

        underlayLevels[index]?.let { prev ->
            TilePyramid.getVisibleTiles(page, prev, pageVisible).mapTo(activeKeys) { it.key }
        }
    }

    val adjacentBases = if (visiblePages.isEmpty()) emptyList() else {
        ((visiblePages.first - ADJACENT_BASE_PAGES until visiblePages.first) +
            (visiblePages.last + 1..visiblePages.last + ADJACENT_BASE_PAGES))
            .filter { it in layout.pages.indices }
            .map { TilePyramid.computeBaseLevelBounds(layout.pages[it], fitWidthScale) }
    }

    visibleBases.mapTo(activeKeys) { it.key }
    visibleDetail.mapTo(activeKeys) { it.first.key }

    val cancelStaleKeepKeys = activeKeys + prefetch.map { it.first.key } + adjacentBases.map { it.key }

    return TileCollectionPlan(
        visibleBases = visibleBases,
        adjacentBases = adjacentBases,
        visibleDetail = visibleDetail,
        prefetch = prefetch,
        activeKeys = activeKeys,
        cancelStaleKeepKeys = cancelStaleKeepKeys
    )
}

private fun drainEvictedBitmaps(prev: ArrayList<Bitmap>, current: ArrayList<Bitmap>) {
    if (prev.isNotEmpty()) {
        for (bmp in prev) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        prev.clear()
    }
    if (current.isNotEmpty()) {
        prev.addAll(current)
        current.clear()
    }
}

internal fun swapPendingTiles(
    pending: ArrayDeque<Pair<TileKey, Bitmap>>,
    cache: TileCache,
    fadeTimes: HashMap<TileKey, Long>,
    maxSwaps: Int = MAX_TILES_SWAPPED_PER_FRAME,
    onSwapped: (TileKey) -> Unit
) {
    var swapped = 0
    while (swapped < maxSwaps && pending.isNotEmpty()) {
        val (key, bmp) = pending.removeFirst()
        cache.put(key, bmp)
        fadeTimes[key] = TILE_UNREVEALED
        onSwapped(key)
        swapped++
    }
    if (swapped > 0) {
        PerfTrace.mark("${PerfTrace.TILE_SWAPPED}:$swapped")
    }
}

private fun DrawScope.drawBaseLayer(
    cache: TileCache,
    pageInfo: PageInfo,
    offsetX: Float,
    offsetY: Float,
    scale: Float
) {
    val base = cache.get(TileCache.baseLayerKey(pageInfo.index))
    val contentW = (pageInfo.widthPoints * scale).roundToInt()
    val contentH = (pageInfo.heightPoints * scale).roundToInt()
    if (contentW <= 0 || contentH <= 0) return
    if (base != null && !base.isRecycled) {
        drawImage(
            image = base.asImageBitmap(),
            dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
            dstSize = IntSize(contentW, contentH)
        )
    } else {
        val thumb = cache.getThumbnail(pageInfo.index)
        if (thumb != null && !thumb.isRecycled) {
            drawImage(
                image = thumb.asImageBitmap(),
                dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
                dstSize = IntSize(contentW, contentH)
            )
        } else {
            drawRect(
                color = PAGE_PLACEHOLDER_COLOR,
                topLeft = Offset(offsetX.roundToInt().toFloat(), offsetY.roundToInt().toFloat()),
                size = Size(contentW.toFloat(), contentH.toFloat())
            )
        }
    }
}

/** A visible page this frame: its visible part in page points, its level, and its visible tiles. */
private class PageFrame(
    val index: Int,
    val pageVisible: ViewportRect,
    val level: PyramidLevel,
    val tiles: List<TileBounds>
)

/**
 * Draws one page: base layer (or placeholder), the previous level as underlay until the current
 * level is revealed over the visible part, then the current level's tiles. [offsetX]/[offsetY] are
 * the page's screen origin. Returns true while any tile is still crossfading.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawTilePyramid(
    page: PageInfo,
    frame: PageFrame,
    tileCache: TileCache,
    tileFadeStartTimes: HashMap<TileKey, Long>,
    underlayLevels: HashMap<Int, PyramidLevel>,
    now: Long,
    scale: Float,
    offsetX: Float,
    offsetY: Float
): Boolean {
    val currentLevel = frame.level
    val visibleTiles = frame.tiles
    val pageVisible = frame.pageVisible

    // Skip the full-page base layer when opaque detail tiles cover the visible page: on a
    // full-screen page it is a whole extra screen of GPU fill per frame (ADR 0004).
    // If only some tiles are fading or missing, only draw the base layer clipped to those tiles (M6-03).
    val nonOpaque = nonOpaqueTiles(visibleTiles, { tileCache.get(it) != null }, tileFadeStartTimes, now)
    val detailOpaque = visibleTiles.isNotEmpty() && nonOpaque.isEmpty()
    if (!detailOpaque) {
        if (visibleTiles.isEmpty() || nonOpaque.size == visibleTiles.size) {
            val left = (offsetX + pageVisible.left * scale).roundToInt().toFloat()
            val top = (offsetY + pageVisible.top * scale).roundToInt().toFloat()
            val right = (offsetX + pageVisible.right * scale).roundToInt().toFloat()
            val bottom = (offsetY + pageVisible.bottom * scale).roundToInt().toFloat()
            if (right > left && bottom > top) {
                clipRect(left, top, right, bottom) {
                    drawBaseLayer(tileCache, page, offsetX, offsetY, scale)
                }
            }
        } else if (nonOpaque.size <= 4) {
            for (tile in nonOpaque) {
                val left = (offsetX + tile.pageRect.left * scale).roundToInt().toFloat()
                val top = (offsetY + tile.pageRect.top * scale).roundToInt().toFloat()
                val right = (offsetX + tile.pageRect.right * scale).roundToInt().toFloat()
                val bottom = (offsetY + tile.pageRect.bottom * scale).roundToInt().toFloat()
                if (right > left && bottom > top) {
                    clipRect(left, top, right, bottom) {
                        drawBaseLayer(tileCache, page, offsetX, offsetY, scale)
                    }
                }
            }
        } else {
            val minLeft = nonOpaque.minOf { it.pageRect.left }
            val minTop = nonOpaque.minOf { it.pageRect.top }
            val maxRight = nonOpaque.maxOf { it.pageRect.right }
            val maxBottom = nonOpaque.maxOf { it.pageRect.bottom }
            val left = (offsetX + minLeft * scale).roundToInt().toFloat()
            val top = (offsetY + minTop * scale).roundToInt().toFloat()
            val right = (offsetX + maxRight * scale).roundToInt().toFloat()
            val bottom = (offsetY + maxBottom * scale).roundToInt().toFloat()
            if (right > left && bottom > top) {
                clipRect(left, top, right, bottom) {
                    drawBaseLayer(tileCache, page, offsetX, offsetY, scale)
                }
            }
        }
    }

    // Keep the previous level underneath until the current one has fully faded in: dropping it
    // when the fade started let the blurry base show through for a frame (a blink on zoom end).
    if (detailOpaque) {
        underlayLevels[page.index] = currentLevel
    } else {
        val prevLevel = underlayLevels[page.index]
        if (prevLevel != null && prevLevel != currentLevel) {
            val underlayTiles = TilePyramid.getVisibleTiles(page, prevLevel, pageVisible)
            drawTileBoundsList(underlayTiles, tileCache, tileFadeStartTimes, offsetX, offsetY, scale, now, alphaOverride = 1f)
        }
    }

    return drawTileBoundsList(visibleTiles, tileCache, tileFadeStartTimes, offsetX, offsetY, scale, now, alphaOverride = null)
}

/**
 * Batched reveal, so the view sharpens in one fade instead of a checkerboard of squares:
 * tiles that arrive while visible stay hidden ([TILE_UNREVEALED]) until every visible tile is
 * loaded, or the batch has waited [timeoutMs]; then they all start fading together.
 * Tiles that arrived off-screen (prefetch) are marked fully faded, so they scroll in already sharp.
 * Returns when the current batch started waiting, or null when nothing is waiting.
 */
internal fun revealTiles(
    visibleTiles: List<TileBounds>,
    isLoaded: (TileKey) -> Boolean,
    fadeStartTimes: MutableMap<TileKey, Long>,
    pendingSince: Long?,
    now: Long,
    timeoutMs: Long = TILE_REVEAL_TIMEOUT_MS
): Long? {
    val visibleKeys = visibleTiles.mapTo(HashSet()) { it.key }
    var waiting = false
    for (entry in fadeStartTimes.entries) {
        if (entry.value != TILE_UNREVEALED) continue
        if (entry.key in visibleKeys) waiting = true else entry.setValue(now - TILE_CROSSFADE_DURATION_MS)
    }
    if (!waiting) return null
    val since = pendingSince ?: now
    if (visibleTiles.all { isLoaded(it.key) } || now - since >= timeoutMs) {
        PerfTrace.mark(PerfTrace.REVEAL_START)
        for (tile in visibleTiles) {
            if (fadeStartTimes[tile.key] == TILE_UNREVEALED) fadeStartTimes[tile.key] = now
        }
        return null
    }
    return since
}

/**
 * True when every visible page's detail layer is opaque: where the PRD §7 "sharp" timing ends,
 * crossfade included.
 */
internal fun isViewSharp(
    visibleTilesPerPage: List<List<TileBounds>>,
    isLoaded: (TileKey) -> Boolean,
    fadeStartTimes: Map<TileKey, Long>,
    now: Long
): Boolean = visibleTilesPerPage.isNotEmpty() &&
    visibleTilesPerPage.all { isDetailLayerOpaque(it, isLoaded, fadeStartTimes, now) }

/** True when every visible detail tile is loaded and has finished its crossfade. */
internal fun isDetailLayerOpaque(
    visibleTiles: List<TileBounds>,
    isLoaded: (TileKey) -> Boolean,
    fadeStartTimes: Map<TileKey, Long>,
    now: Long
): Boolean = visibleTiles.isNotEmpty() && visibleTiles.all { tile ->
    isLoaded(tile.key) && now - (fadeStartTimes[tile.key] ?: now) >= TILE_CROSSFADE_DURATION_MS
}

/** Returns the subset of [visibleTiles] that are missing or still crossfading. */
internal fun nonOpaqueTiles(
    visibleTiles: List<TileBounds>,
    isLoaded: (TileKey) -> Boolean,
    fadeStartTimes: Map<TileKey, Long>,
    now: Long
): List<TileBounds> = visibleTiles.filter { tile ->
    !isLoaded(tile.key) || now - (fadeStartTimes[tile.key] ?: now) < TILE_CROSSFADE_DURATION_MS
}

@Suppress("LongParameterList")
private fun DrawScope.drawTileBoundsList(
    tiles: List<TileBounds>,
    cache: TileCache,
    fadeTimes: HashMap<TileKey, Long>,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    now: Long,
    alphaOverride: Float?
): Boolean {
    var hasActiveFade = false
    for (tile in tiles) {
        val bmp = cache.get(tile.key)
        if (bmp != null && !bmp.isRecycled) {
            val alpha = if (alphaOverride != null) {
                alphaOverride
            } else {
                val fadeStart = fadeTimes[tile.key] ?: now
                val elapsed = now - fadeStart
                val calcAlpha = (elapsed.toFloat() / TILE_CROSSFADE_DURATION_MS).coerceIn(0f, 1f)
                if (calcAlpha < 1f) hasActiveFade = true
                calcAlpha
            }

            val leftPx = (offsetX + tile.pageRect.left * scale).roundToInt()
            val topPx = (offsetY + tile.pageRect.top * scale).roundToInt()
            val rightPx = (offsetX + tile.pageRect.right * scale).roundToInt()
            val bottomPx = (offsetY + tile.pageRect.bottom * scale).roundToInt()
            val tileDstW = rightPx - leftPx
            val tileDstH = bottomPx - topPx

            if (tileDstW > 0 && tileDstH > 0 && alpha > 0f) {
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstOffset = IntOffset(leftPx, topPx),
                    dstSize = IntSize(tileDstW, tileDstH),
                    alpha = alpha
                )
            }
        }
    }
    return hasActiveFade
}

private data class SurfaceViewportSnapshot(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val reloadTrigger: Int,
    val isZooming: Boolean,
    val viewportWidth: Float,
    val viewportHeight: Float
)
