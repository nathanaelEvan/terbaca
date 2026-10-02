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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.terbaca.core.model.TileKey
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.cache.TileCache
import app.terbaca.core.render.cache.TileMemoryBudget
import app.terbaca.core.render.pyramid.PyramidLevel
import app.terbaca.core.render.pyramid.TileBounds
import app.terbaca.core.render.pyramid.TilePyramid
import app.terbaca.core.render.scheduler.RenderScheduler
import app.terbaca.core.render.scheduler.TileLayerType
import app.terbaca.core.render.scheduler.TilePriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

const val MAX_TILES_SWAPPED_PER_FRAME = 4
const val TILE_CROSSFADE_DURATION_MS = 120L
private const val RENDER_SCHEDULER_CONCURRENCY = 2

private class IntSizeRef(var width: Int = 0, var height: Int = 0)
private class LevelRef(var level: PyramidLevel? = null)

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
    val isBaseLayerInFlight = remember { AtomicBoolean(false) }

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
    val underlayLevel = remember { LevelRef() }
    val viewportGeneration = remember { AtomicLong(0L) }

    val sizeModifier = Modifier.onSizeChanged { size ->
        handleSizeChanged(
            size = size,
            lastSize = lastSize,
            tileCache = tileCache,
            tileFadeStartTimes = tileFadeStartTimes,
            pendingTileArrivals = pendingTileArrivals,
            scheduler = scheduler,
            underlayLevel = underlayLevel,
            state = state,
            inFlightTiles = inFlightTiles,
            isBaseLayerInFlight = isBaseLayerInFlight,
            viewportGeneration = viewportGeneration
        )
    }
    val gestureModifier = Modifier.documentGestures(state, coroutineScope, decay)

    DocumentTileCollector(
        state = state,
        renderer = renderer,
        docHandle = docHandle,
        scheduler = scheduler,
        tileCache = tileCache,
        tileFadeStartTimes = tileFadeStartTimes,
        pendingTileArrivals = pendingTileArrivals,
        inFlightTiles = inFlightTiles,
        isBaseLayerInFlight = isBaseLayerInFlight,
        underlayLevel = underlayLevel,
        tileArrivals = tileArrivals,
        reloadTrigger = reloadTrigger,
        coroutineScope = coroutineScope,
        viewportGeneration = viewportGeneration
    )

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
            swapPendingTiles(pendingTileArrivals, tileCache, tileFadeStartTimes, now)

            drawBaseLayer(tileCache, state.pageInfo, offsetX, offsetY, scale)

            var hasActiveFade = false
            if (state.viewportWidth > 0f && state.viewportHeight > 0f) {
                hasActiveFade = drawTilePyramid(
                    tileCache = tileCache,
                    tileFadeStartTimes = tileFadeStartTimes,
                    underlayLevel = underlayLevel,
                    state = state,
                    now = now,
                    scale = scale,
                    offsetX = offsetX,
                    offsetY = offsetY
                )
            }

            if (hasActiveFade || pendingTileArrivals.isNotEmpty()) {
                frameClock.longValue = now
            }
            onSurfaceDrawn?.invoke()
        }
    }
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
    underlayLevel: LevelRef,
    state: DocumentTransformState,
    inFlightTiles: HashSet<TileKey>,
    isBaseLayerInFlight: AtomicBoolean,
    viewportGeneration: AtomicLong
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
            isBaseLayerInFlight.set(false)
            underlayLevel.level = null
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
    reloadTrigger: androidx.compose.runtime.MutableIntState,
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
    decay: DecayAnimationSpec<Float>
): Modifier = pointerInput(state) {
    val velocityTracker = VelocityTracker()
    var flingJob: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        flingJob?.cancel()
        flingJob = null
        velocityTracker.resetTracking()
        velocityTracker.addPointerInputChange(down)

        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

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
                        if (zoomChange != 1f) state.onPinch(zoomChange, centroid)
                        if (panChange != Offset.Zero) state.onPan(panChange.x, panChange.y)
                    }
                    event.changes.forEach { change: PointerInputChange ->
                        if (change.position != change.previousPosition) change.consume()
                        velocityTracker.addPointerInputChange(change)
                    }
                }
            }
        } while (event.changes.any { it.pressed })

        if (pastTouchSlop) {
            val velocity = velocityTracker.calculateVelocity()
            flingJob = coroutineScope.launch {
                state.fling(velocity.x, velocity.y, decay)
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun DocumentTileCollector(
    state: DocumentTransformState,
    renderer: PageRenderer?,
    docHandle: Long,
    scheduler: RenderScheduler<Bitmap>?,
    tileCache: TileCache,
    tileFadeStartTimes: HashMap<TileKey, Long>,
    pendingTileArrivals: ArrayDeque<Pair<TileKey, Bitmap>>,
    inFlightTiles: HashSet<TileKey>,
    isBaseLayerInFlight: AtomicBoolean,
    underlayLevel: LevelRef,
    tileArrivals: androidx.compose.runtime.MutableIntState,
    reloadTrigger: androidx.compose.runtime.MutableIntState,
    coroutineScope: CoroutineScope,
    viewportGeneration: AtomicLong
) {
    LaunchedEffect(state, renderer, docHandle) {
        if (renderer == null || docHandle == 0L || scheduler == null) return@LaunchedEffect

        val pageInfo = state.pageInfo
        val baseKey = TileCache.baseLayerKey(pageInfo.index)

        snapshotFlow {
            SurfaceViewportSnapshot(state.scale, state.offsetX, state.offsetY, reloadTrigger.intValue)
        }.collectLatest { (scale, _, _, _) ->
            if (state.viewportWidth <= 0f || state.viewportHeight <= 0f) return@collectLatest

            enqueueBaseLayerIfNeeded(
                pageInfo = pageInfo,
                fitWidthScale = state.fitWidthScale,
                baseKey = baseKey,
                tileCache = tileCache,
                isBaseLayerInFlight = isBaseLayerInFlight,
                scheduler = scheduler,
                coroutineScope = coroutineScope,
                pendingTileArrivals = pendingTileArrivals,
                tileArrivals = tileArrivals,
                viewportGeneration = viewportGeneration
            )

            val levels = TilePyramid.buildLevels(pageInfo, state.viewportWidth, state.viewportHeight)
            val currentLevel = TilePyramid.selectLevel(levels, scale)
            val visibleRect = state.getVisibleRectPoints()
            val visibleTiles = TilePyramid.getVisibleTiles(pageInfo, currentLevel, visibleRect)

            val activeKeys = HashSet<TileKey>()
            activeKeys.add(baseKey)
            visibleTiles.mapTo(activeKeys) { it.key }
            underlayLevel.level?.let { prev ->
                TilePyramid.getVisibleTiles(pageInfo, prev, visibleRect).mapTo(activeKeys) { it.key }
            }
            scheduler.cancelStaleExcept(activeKeys)

            tileCache.pinHot(activeKeys)
            tileFadeStartTimes.keys.retainAll { tileCache.get(it) != null }

            enqueueVisibleTiles(
                visibleTiles = visibleTiles,
                tileCache = tileCache,
                inFlightTiles = inFlightTiles,
                scheduler = scheduler,
                coroutineScope = coroutineScope,
                pendingTileArrivals = pendingTileArrivals,
                tileArrivals = tileArrivals,
                state = state,
                scale = scale,
                viewportGeneration = viewportGeneration
            )
        }
    }
}

@Suppress("LongParameterList")
private fun enqueueBaseLayerIfNeeded(
    pageInfo: app.terbaca.core.model.PageInfo,
    fitWidthScale: Float,
    baseKey: TileKey,
    tileCache: TileCache,
    isBaseLayerInFlight: AtomicBoolean,
    scheduler: RenderScheduler<Bitmap>,
    coroutineScope: CoroutineScope,
    pendingTileArrivals: ArrayDeque<Pair<TileKey, Bitmap>>,
    tileArrivals: androidx.compose.runtime.MutableIntState,
    viewportGeneration: AtomicLong
) {
    val baseBounds = TilePyramid.computeBaseLevelBounds(pageInfo, fitWidthScale)
    if (baseBounds.pixelWidth > 0 && baseBounds.pixelHeight > 0 &&
        tileCache.get(baseKey) == null &&
        isBaseLayerInFlight.compareAndSet(false, true)
    ) {
        val currentGen = viewportGeneration.get()
        val deferred = scheduler.enqueue(baseBounds, TilePriority(TileLayerType.VISIBLE_BASE))
        coroutineScope.launch {
            var bmp: Bitmap? = null
            try {
                bmp = deferred.await()
                if (viewportGeneration.get() != currentGen) {
                    if (!bmp.isRecycled) bmp.recycle()
                    return@launch
                }
                pendingTileArrivals.add(baseKey to bmp)
                tileArrivals.intValue++
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    bmp?.let { if (!it.isRecycled) it.recycle() }
                }
                // Retried on next viewport update
            } finally {
                isBaseLayerInFlight.set(false)
            }
        }
    }
}

@Suppress("LongParameterList")
private fun enqueueVisibleTiles(
    visibleTiles: List<TileBounds>,
    tileCache: TileCache,
    inFlightTiles: HashSet<TileKey>,
    scheduler: RenderScheduler<Bitmap>,
    coroutineScope: CoroutineScope,
    pendingTileArrivals: ArrayDeque<Pair<TileKey, Bitmap>>,
    tileArrivals: androidx.compose.runtime.MutableIntState,
    state: DocumentTransformState,
    scale: Float,
    viewportGeneration: AtomicLong
) {
    val viewportCenter = Offset(state.viewportWidth / 2f, state.viewportHeight / 2f)
    for (tile in visibleTiles) {
        if (tileCache.get(tile.key) == null && inFlightTiles.add(tile.key)) {
            val tileCenter = Offset(
                state.offsetX + tile.pageRect.left * scale + (tile.pageRect.width * scale) / 2f,
                state.offsetY + tile.pageRect.top * scale + (tile.pageRect.height * scale) / 2f
            )
            val dist = (tileCenter - viewportCenter).getDistance()
            val priority = TilePriority(TileLayerType.VISIBLE_DETAIL, distanceToCenter = dist)
            val currentGen = viewportGeneration.get()
            val deferred = scheduler.enqueue(tile, priority)
            coroutineScope.launch {
                var bmp: Bitmap? = null
                try {
                    bmp = deferred.await()
                    if (viewportGeneration.get() != currentGen) {
                        if (!bmp.isRecycled) bmp.recycle()
                        return@launch
                    }
                    pendingTileArrivals.add(tile.key to bmp)
                    tileArrivals.intValue++
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        bmp?.let { if (!it.isRecycled) it.recycle() }
                    }
                    // Retried on next viewport update
                } finally {
                    inFlightTiles.remove(tile.key)
                }
            }
        }
    }
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

private fun swapPendingTiles(
    pending: ArrayDeque<Pair<TileKey, Bitmap>>,
    cache: TileCache,
    fadeTimes: HashMap<TileKey, Long>,
    now: Long
) {
    var swapped = 0
    while (swapped < MAX_TILES_SWAPPED_PER_FRAME && pending.isNotEmpty()) {
        val (key, bmp) = pending.removeFirst()
        cache.put(key, bmp)
        fadeTimes[key] = now
        swapped++
    }
}

private fun DrawScope.drawBaseLayer(
    cache: TileCache,
    pageInfo: app.terbaca.core.model.PageInfo,
    offsetX: Float,
    offsetY: Float,
    scale: Float
) {
    val base = cache.get(TileCache.baseLayerKey(pageInfo.index))
    val contentW = (pageInfo.widthPoints * scale).roundToInt()
    val contentH = (pageInfo.heightPoints * scale).roundToInt()
    if (base != null && !base.isRecycled && contentW > 0 && contentH > 0) {
        drawImage(
            image = base.asImageBitmap(),
            dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
            dstSize = IntSize(contentW, contentH)
        )
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawTilePyramid(
    tileCache: TileCache,
    tileFadeStartTimes: HashMap<TileKey, Long>,
    underlayLevel: LevelRef,
    state: DocumentTransformState,
    now: Long,
    scale: Float,
    offsetX: Float,
    offsetY: Float
): Boolean {
    val levels = TilePyramid.buildLevels(state.pageInfo, state.viewportWidth, state.viewportHeight)
    val currentLevel = TilePyramid.selectLevel(levels, scale)
    val visibleRect = state.getVisibleRectPoints()
    val visibleTiles = TilePyramid.getVisibleTiles(state.pageInfo, currentLevel, visibleRect)

    val allCurrentLoaded = visibleTiles.all { tileCache.get(it.key) != null }
    if (allCurrentLoaded) {
        underlayLevel.level = currentLevel
    } else {
        val prevLevel = underlayLevel.level
        if (prevLevel != null && prevLevel != currentLevel) {
            val underlayTiles = TilePyramid.getVisibleTiles(state.pageInfo, prevLevel, visibleRect)
            drawTileBoundsList(underlayTiles, tileCache, tileFadeStartTimes, offsetX, offsetY, scale, now, alphaOverride = 1f)
        }
    }

    return drawTileBoundsList(visibleTiles, tileCache, tileFadeStartTimes, offsetX, offsetY, scale, now, alphaOverride = null)
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

            if (tileDstW > 0 && tileDstH > 0) {
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
    val reloadTrigger: Int
)
