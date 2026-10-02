package app.terbaca.feature.reader

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.exponentialDecay
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.terbaca.core.model.TileKey
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.pyramid.PyramidLevel
import app.terbaca.core.render.pyramid.TileBounds
import app.terbaca.core.render.pyramid.TilePyramid
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Custom single composable rendering the document surface per PRD §4.4 and §4.6.
 *
 * Rules:
 * - Pan and zoom state is read ONLY during the draw phase (Canvas draw scope),
 *   ensuring zero recompositions during gestures.
 * - Gestures: Pinch focal point anchoring, pan, and fling decay.
 * - Layers: Base level whole-page bitmap + detail tiles with 120ms crossfade.
 * - Tile swap rate is capped at [MAX_TILES_SWAPPED_PER_FRAME] per frame.
 */
const val MAX_TILES_SWAPPED_PER_FRAME = 4
const val TILE_CROSSFADE_DURATION_MS = 120L

@Composable
fun DocumentSurface(
    modifier: Modifier = Modifier,
    state: DocumentTransformState,
    renderer: PageRenderer? = null,
    docHandle: Long = 0L,
    onSurfaceDrawn: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val decay = remember { exponentialDecay<Float>() }

    // High refresh rate hint per PRD §4.6
    val view = LocalView.current
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

    // Tile storage and crossfade tracking
    val loadedTiles = remember { mutableStateMapOf<TileKey, Bitmap>() }
    val tileFadeStartTimes = remember { mutableStateMapOf<TileKey, Long>() }
    val pendingTileArrivals = remember { ArrayDeque<Pair<TileKey, Bitmap>>() }
    var baseBitmap = remember { mutableStateMapOf<Int, Bitmap>() }

    // Animation frame clock for crossfade progression
    val frameClock = remember { mutableLongStateOf(0L) }

    // Measure viewport on size change
    val sizeModifier = Modifier.onSizeChanged { size ->
        state.updateViewport(size.width.toFloat(), size.height.toFloat())
    }

    // Gesture handling: Pan, Pinch with focal point anchoring, Fling
    val gestureModifier = Modifier.pointerInput(state) {
        val velocityTracker = VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
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
                        val totalPan = pan.getDistance()
                        if (totalPan > touchSlop || zoom < 0.95f || zoom > 1.05f) {
                            pastTouchSlop = true
                        }
                    }

                    if (pastTouchSlop) {
                        if (centroid != Offset.Unspecified) {
                            if (zoomChange != 1f) {
                                state.onPinch(zoomChange, centroid)
                            }
                            if (panChange != Offset.Zero) {
                                state.onPan(panChange.x, panChange.y)
                            }
                        }
                        event.changes.forEach { change: PointerInputChange ->
                            if (change.position != change.previousPosition) {
                                change.consume()
                            }
                            velocityTracker.addPointerInputChange(change)
                        }
                    }
                }
            } while (event.changes.any { it.pressed })

            if (pastTouchSlop) {
                val velocity = velocityTracker.calculateVelocity()
                coroutineScope.launch {
                    state.fling(velocity.x, velocity.y, decay)
                }
            }
        }
    }

    // Asynchronous tile fetching reacting to viewport changes via snapshotFlow without recomposing
    LaunchedEffect(state, renderer, docHandle) {
        if (renderer == null || docHandle == 0L) return@LaunchedEffect

        // Render base level at fit-width scale once
        val pageInfo = state.pageInfo
        coroutineScope.launch {
            val fitWidthPx = (pageInfo.widthPoints * state.fitWidthScale).roundToInt()
            val fitHeightPx = (pageInfo.heightPoints * state.fitWidthScale).roundToInt()
            if (fitWidthPx > 0 && fitHeightPx > 0 && !baseBitmap.containsKey(pageInfo.index)) {
                runCatching {
                    val bmp = renderer.renderTile(
                        docHandle = docHandle,
                        pageIndex = pageInfo.index,
                        fullWidth = fitWidthPx,
                        fullHeight = fitHeightPx,
                        startX = 0,
                        startY = 0,
                        tileWidth = fitWidthPx,
                        tileHeight = fitHeightPx
                    )
                    baseBitmap[pageInfo.index] = bmp
                }
            }
        }

        // Monitor viewport updates without triggering recomposition of DocumentSurface
        snapshotFlow {
            Triple(state.scale, state.offsetX, state.offsetY)
        }.collectLatest { (scale, _, _) ->
            if (state.viewportWidth <= 0f || state.viewportHeight <= 0f) return@collectLatest

            val levels = TilePyramid.buildLevels(
                pageInfo = pageInfo,
                viewportWidth = state.viewportWidth,
                viewportHeight = state.viewportHeight
            )
            val currentLevel = TilePyramid.selectLevel(levels, scale)
            val visibleRect = state.getVisibleRectPoints()
            val visibleTiles = TilePyramid.getVisibleTiles(pageInfo, currentLevel, visibleRect)

            for (tile in visibleTiles) {
                if (!loadedTiles.containsKey(tile.key)) {
                    coroutineScope.launch {
                        runCatching {
                            val startX = tile.key.col * currentLevel.tileSize
                            val startY = tile.key.row * currentLevel.tileSize
                            val bmp = renderer.renderTile(
                                docHandle = docHandle,
                                pageIndex = tile.key.pageIndex,
                                fullWidth = currentLevel.totalPixelWidth,
                                fullHeight = currentLevel.totalPixelHeight,
                                startX = startX,
                                startY = startY,
                                tileWidth = tile.pixelWidth,
                                tileHeight = tile.pixelHeight
                            )
                            pendingTileArrivals.add(tile.key to bmp)
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(sizeModifier)
            .then(gestureModifier)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read state STRICTLY in the draw phase (zero recompositions during gestures)
            val scale = state.scale
            val offsetX = state.offsetX
            val offsetY = state.offsetY
            val now = System.currentTimeMillis()

            // Drain up to MAX_TILES_SWAPPED_PER_FRAME pending tiles per frame
            var swapped = 0
            while (swapped < MAX_TILES_SWAPPED_PER_FRAME && pendingTileArrivals.isNotEmpty()) {
                val (key, bmp) = pendingTileArrivals.removeFirst()
                loadedTiles[key] = bmp
                tileFadeStartTimes[key] = now
                swapped++
            }

            // 1. Draw base layer
            val base = baseBitmap[state.pageInfo.index]
            val contentW = (state.pageInfo.widthPoints * scale).roundToInt()
            val contentH = (state.pageInfo.heightPoints * scale).roundToInt()

            if (base != null && !base.isRecycled) {
                drawImage(
                    image = base.asImageBitmap(),
                    dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
                    dstSize = IntSize(contentW, contentH)
                )
            }

            // 2. Draw detail tiles with crossfade
            var hasActiveFade = false
            if (state.viewportWidth > 0f && state.viewportHeight > 0f) {
                val levels = TilePyramid.buildLevels(
                    pageInfo = state.pageInfo,
                    viewportWidth = state.viewportWidth,
                    viewportHeight = state.viewportHeight
                )
                val currentLevel = TilePyramid.selectLevel(levels, scale)
                val visibleRect = state.getVisibleRectPoints()
                val visibleTiles = TilePyramid.getVisibleTiles(state.pageInfo, currentLevel, visibleRect)

                for (tile in visibleTiles) {
                    val bmp = loadedTiles[tile.key]
                    if (bmp != null && !bmp.isRecycled) {
                        val fadeStart = tileFadeStartTimes[tile.key] ?: now
                        val elapsed = now - fadeStart
                        val alpha = (elapsed.toFloat() / TILE_CROSSFADE_DURATION_MS).coerceIn(0f, 1f)
                        if (alpha < 1f) {
                            hasActiveFade = true
                        }

                        val tileDstX = (offsetX + tile.pageRect.left * scale).roundToInt()
                        val tileDstY = (offsetY + tile.pageRect.top * scale).roundToInt()
                        val tileDstW = (tile.pageRect.width * scale).roundToInt()
                        val tileDstH = (tile.pageRect.height * scale).roundToInt()

                        if (tileDstW > 0 && tileDstH > 0) {
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(tileDstX, tileDstY),
                                dstSize = IntSize(tileDstW, tileDstH),
                                alpha = alpha
                            )
                        }
                    }
                }
            }

            // Keep triggering frames until all newly swapped tiles finish crossfading
            if (hasActiveFade || pendingTileArrivals.isNotEmpty()) {
                frameClock.longValue = now
            }

            onSurfaceDrawn?.invoke()
        }
    }
}
