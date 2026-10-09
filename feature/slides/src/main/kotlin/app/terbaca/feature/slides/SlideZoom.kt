package app.terbaca.feature.slides

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Zoom limits relative to the fitted slide: fit to 800% (F-NAV-1). */
const val SLIDE_MIN_ZOOM = 1f
const val SLIDE_MAX_ZOOM = 8f

/** Zoom a double tap on a fitted slide goes to; a double tap while zoomed returns to fit. */
const val SLIDE_DOUBLE_TAP_ZOOM = 2.5f

/** Largest tile the render service accepts per side (`PdfiumDocument.MAX_TILE_DIMENSION`). */
const val SLIDE_MAX_DETAIL_TILE_PX = 4096

private const val ZOOM_EPSILON = 0.01f

/**
 * Pinch/pan transform of the current slide (F-NAV-1).
 *
 * The fitted slide is drawn centred in the viewport; this adds a scale around the viewport centre
 * plus a translation in screen pixels. The values are snapshot state that the slide reads only in
 * its `graphicsLayer`/draw lambdas (PRD §4.6), so a gesture never recomposes the pager. The only
 * composition read is [isZoomed] through `derivedStateOf`, which flips once when the slide leaves
 * or returns to fit and locks/unlocks pager swiping.
 */
@Stable
class SlideZoomState {
    var scale by mutableFloatStateOf(SLIDE_MIN_ZOOM)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** True during a gesture, fling or double-tap animation; the sharp re-render waits for false. */
    var isInteracting by mutableStateOf(false)
        internal set

    private var viewportWidth = 0f
    private var viewportHeight = 0f
    private var contentWidth = 0f
    private var contentHeight = 0f

    /** Pager swiping is locked while this is true; it resumes once the slide is back at fit. */
    val isZoomed: Boolean
        get() = isZoomedIn(scale)

    /** Viewport and fitted-slide sizes in px, from the slide the gesture is on. */
    fun setGeometry(viewportWidth: Float, viewportHeight: Float, contentWidth: Float, contentHeight: Float) {
        this.viewportWidth = viewportWidth
        this.viewportHeight = viewportHeight
        this.contentWidth = contentWidth
        this.contentHeight = contentHeight
        clampOffsets()
    }

    /**
     * Scales by [zoomChange] while keeping the slide point under ([focusX], [focusY]) (viewport
     * px) in place, then clamps to [SLIDE_MIN_ZOOM]..[SLIDE_MAX_ZOOM] and the slide's edges.
     */
    fun onPinch(zoomChange: Float, focusX: Float, focusY: Float) {
        val newScale = (scale * zoomChange).coerceIn(SLIDE_MIN_ZOOM, SLIDE_MAX_ZOOM)
        if (newScale == scale) return
        val ratio = newScale / scale
        // Focus relative to the transform origin (the viewport centre).
        val fx = focusX - viewportWidth / 2f
        val fy = focusY - viewportHeight / 2f
        offsetX = fx - (fx - offsetX) * ratio
        offsetY = fy - (fy - offsetY) * ratio
        scale = newScale
        clampOffsets()
    }

    fun onPan(dx: Float, dy: Float) {
        offsetX += dx
        offsetY += dy
        clampOffsets()
    }

    /** Back to fit, e.g. when the slide changes or the viewport resizes. */
    fun reset() {
        scale = SLIDE_MIN_ZOOM
        offsetX = 0f
        offsetY = 0f
    }

    /** Pan momentum after a zoomed drag; each axis stops at the slide's edge. */
    suspend fun fling(velocityX: Float, velocityY: Float, decay: DecayAnimationSpec<Float>) = coroutineScope {
        launch {
            AnimationState(offsetX, velocityX).animateDecay(decay) {
                offsetX = value
                clampOffsets()
                if (offsetX != value) cancelAnimation()
            }
        }
        launch {
            AnimationState(offsetY, velocityY).animateDecay(decay) {
                offsetY = value
                clampOffsets()
                if (offsetY != value) cancelAnimation()
            }
        }
    }

    /** Double tap: zoomed → fit, fit → [SLIDE_DOUBLE_TAP_ZOOM] anchored at the tap. */
    suspend fun animateDoubleTap(focusX: Float, focusY: Float) {
        val target = doubleTapTargetZoom(scale)
        animate(initialValue = scale, targetValue = target) { value, _ ->
            onPinch(value / scale, focusX, focusY)
        }
    }

    private fun clampOffsets() {
        val maxX = maxOffset(contentWidth, viewportWidth, scale)
        val maxY = maxOffset(contentHeight, viewportHeight, scale)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }
}

internal fun isZoomedIn(scale: Float): Boolean = scale > SLIDE_MIN_ZOOM + ZOOM_EPSILON

internal fun doubleTapTargetZoom(scale: Float): Float = if (isZoomedIn(scale)) SLIDE_MIN_ZOOM else SLIDE_DOUBLE_TAP_ZOOM

/** How far the scaled slide may move off-centre before an edge would leave the viewport's edge. */
internal fun maxOffset(contentSize: Float, viewportSize: Float, scale: Float): Float =
    max(0f, (contentSize * scale - viewportSize) / 2f)

/**
 * The part of a slide to re-render sharp at the current zoom, in the pixels of the slide rendered
 * at full size [fullWidth] x [fullHeight] (the fitted size times the zoom, i.e. 1:1 on screen).
 */
data class SlideDetailRegion(
    val fullWidth: Int,
    val fullHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

/**
 * Visible part of a fitted [contentWidth] x [contentHeight] slide, centred in a
 * [viewportWidth] x [viewportHeight] viewport and transformed by [scale]/[offsetX]/[offsetY]
 * around the viewport centre. Null at fit, where the fitted bitmap is already 1:1 on screen.
 * Each side is capped at [maxTilePx]; anything beyond keeps the upscaled fitted bitmap.
 */
fun visibleDetailRegion(
    viewportWidth: Int,
    viewportHeight: Int,
    contentWidth: Int,
    contentHeight: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    maxTilePx: Int = SLIDE_MAX_DETAIL_TILE_PX
): SlideDetailRegion? {
    if (!isZoomedIn(scale) || viewportWidth <= 0 || viewportHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) {
        return null
    }
    val fullWidth = (contentWidth * scale).roundToInt()
    val fullHeight = (contentHeight * scale).roundToInt()
    // Screen position of the slide's top-left corner.
    val originX = viewportWidth / 2f - fullWidth / 2f + offsetX
    val originY = viewportHeight / 2f - fullHeight / 2f + offsetY
    val left = floor(-originX).toInt().coerceIn(0, fullWidth)
    val top = floor(-originY).toInt().coerceIn(0, fullHeight)
    val right = ceil(viewportWidth - originX).toInt().coerceIn(0, fullWidth)
    val bottom = ceil(viewportHeight - originY).toInt().coerceIn(0, fullHeight)
    val width = (right - left).coerceAtMost(maxTilePx)
    val height = (bottom - top).coerceAtMost(maxTilePx)
    if (width <= 0 || height <= 0) return null
    return SlideDetailRegion(fullWidth, fullHeight, left, top, width, height)
}

/**
 * Fling velocity from the gesture's pan (the fingers' centroid) only, restarting when the finger
 * count changes, so lifting one finger of a pinch doesn't read as a fast jump (same approach as
 * the reader's document surface).
 */
internal class SlidePanVelocityTracker {
    private val tracker = VelocityTracker()
    private var position = Offset.Zero
    private var pointerCount = 1

    fun reset(downTimeMillis: Long) {
        tracker.resetTracking()
        position = Offset.Zero
        pointerCount = 1
        tracker.addPosition(downTimeMillis, position)
    }

    fun add(timeMillis: Long, pan: Offset, pressedPointers: Int) {
        if (pressedPointers == 0) return
        if (pressedPointers != pointerCount) {
            tracker.resetTracking()
            pointerCount = pressedPointers
        }
        position += pan
        tracker.addPosition(timeMillis, position)
    }

    fun velocity(): Velocity = tracker.calculateVelocity()
}
