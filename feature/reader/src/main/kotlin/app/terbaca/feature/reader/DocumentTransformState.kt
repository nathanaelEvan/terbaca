package app.terbaca.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.terbaca.core.render.pyramid.TilePyramid
import app.terbaca.core.render.pyramid.ViewportRect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * State holder for document pan, zoom, pinch focal anchoring, and fling gestures, over the
 * whole [layout] (all pages, in document points).
 *
 * To guarantee zero recompositions during gestures (PRD §4.6), [scale], [offsetX], and [offsetY]
 * must be read strictly within the draw/layout phase (e.g. `Modifier.graphicsLayer { }` or `Canvas`).
 */
@Stable
class DocumentTransformState(
    val layout: DocumentLayout,
    initialViewportWidth: Float = 0f,
    initialViewportHeight: Float = 0f
) {
    var viewportWidth by mutableFloatStateOf(initialViewportWidth)
        private set

    var viewportHeight by mutableFloatStateOf(initialViewportHeight)
        private set

    var fitPageScale by mutableFloatStateOf(1f)
        private set

    var fitWidthScale by mutableFloatStateOf(1f)
        private set

    var minScale by mutableFloatStateOf(1f)
        private set

    var maxScale by mutableFloatStateOf(8f)
        private set

    // Current scale and translation in device pixels.
    // Read only in draw phase to ensure zero recomposition during gestures.
    var scale by mutableFloatStateOf(1f)
        internal set

    var offsetX by mutableFloatStateOf(0f)
        internal set

    var offsetY by mutableFloatStateOf(0f)
        internal set

    /**
     * True while fingers are pinching. The tile level stays frozen at the pinch start
     * ([levelScale]), so the existing tiles just scale with the fingers: swapping levels mid-pinch
     * made text shimmer as tiles rendered for in-between zooms faded in under the moving fingers.
     */
    var isZooming by mutableStateOf(false)
        private set

    private var zoomStartScale = 1f

    /** Scale used to pick the tile level: the pinch-start scale while [isZooming], else [scale]. */
    val levelScale: Float get() = if (isZooming) zoomStartScale else scale

    fun beginZoom() {
        if (isZooming) return
        zoomStartScale = scale
        isZooming = true
    }

    fun endZoom() {
        isZooming = false
    }

    private val animOffsetX = Animatable(0f)
    private val animOffsetY = Animatable(0f)

    /** Runs flings; any other mutation through it (a jump, a scrollbar drag) cancels the fling. */
    private val mutex = MutatorMutex()

    init {
        if (initialViewportWidth > 0f && initialViewportHeight > 0f) {
            updateViewport(initialViewportWidth, initialViewportHeight)
        }
    }

    /**
     * Updates viewport dimensions and recalculates fit scales and bounds.
     */
    fun updateViewport(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        viewportWidth = width
        viewportHeight = height

        // Fit page uses the tallest page, so at minimum zoom any page fits on screen.
        val wPoints = max(1f, layout.width)
        val hPoints = max(1f, layout.maxPageHeight)

        fitWidthScale = width / wPoints
        val fitHeightScale = height / hPoints
        fitPageScale = min(fitWidthScale, fitHeightScale)
        minScale = fitPageScale
        maxScale = fitWidthScale * TilePyramid.DEFAULT_MAX_ZOOM_MULTIPLIER

        if (scale < minScale) {
            scale = fitWidthScale
        }
        scale = scale.coerceIn(minScale, maxScale)
        clampOffsets()
    }

    /**
     * Handles pan gesture by [deltaX] and [deltaY].
     */
    fun onPan(deltaX: Float, deltaY: Float) {
        offsetX += deltaX
        offsetY += deltaY
        clampOffsets()
    }

    /**
     * Handles pinch zoom by [zoomRatio] anchored around [focalPoint].
     *
     * Anchoring formula (PRD §4.6):
     * The point in document coordinates under [focalPoint] remains under [focalPoint] after scaling:
     * offset_new = focal - (focal - offset_old) * (scale_new / scale_old)
     */
    fun onPinch(zoomRatio: Float, focalPoint: Offset) {
        if (zoomRatio == 1f || scale <= 0f) return

        val oldScale = scale
        val targetScale = (scale * zoomRatio).coerceIn(minScale, maxScale)
        if (targetScale == oldScale) return

        val actualRatio = targetScale / oldScale
        val fx = focalPoint.x
        val fy = focalPoint.y

        offsetX = fx - (fx - offsetX) * actualRatio
        offsetY = fy - (fy - offsetY) * actualRatio
        scale = targetScale

        clampOffsets()
    }

    /**
     * Clamps offsets so content never pans beyond bounds or is centered if smaller than viewport.
     */
    fun clampOffsets() {
        val contentW = layout.width * scale
        val contentH = layout.height * scale

        offsetX = if (contentW <= viewportWidth) {
            (viewportWidth - contentW) / 2f
        } else {
            offsetX.coerceIn(viewportWidth - contentW, 0f)
        }

        offsetY = if (contentH <= viewportHeight) {
            (viewportHeight - contentH) / 2f
        } else {
            offsetY.coerceIn(viewportHeight - contentH, 0f)
        }
    }

    /**
     * Executes fling animation with velocity [velocityX] and [velocityY].
     */
    suspend fun fling(
        velocityX: Float,
        velocityY: Float,
        decay: DecayAnimationSpec<Float> = exponentialDecay()
    ) = mutex.mutate {
        coroutineScope {
            animOffsetX.snapTo(offsetX)
            animOffsetY.snapTo(offsetY)

            val jobX = launch {
                animOffsetX.animateDecay(velocityX, decay) {
                    offsetX = value
                    clampOffsets()
                }
            }
            val jobY = launch {
                animOffsetY.animateDecay(velocityY, decay) {
                    offsetY = value
                    clampOffsets()
                }
            }
            jobX.join()
            jobY.join()
        }
    }

    /** Cancels a running fling, e.g. when the user grabs the scrollbar. */
    suspend fun stopFling() = mutex.mutate(MutatePriority.UserInput) {}

    /** Scrollable height beyond the viewport, in pixels (0 when the document fits). */
    private val scrollRangePx: Float get() = max(0f, layout.height * scale - viewportHeight)

    /** Vertical scroll position in [0, 1]; 0 when the document fits on screen. */
    val scrollFraction: Float
        get() = if (scrollRangePx > 0f) (-offsetY / scrollRangePx).coerceIn(0f, 1f) else 0f

    /** Page under the viewport center (F-NAV-2). */
    val currentPage: Int
        get() = layout.pageAt(((viewportHeight / 2f - offsetY) / max(scale, MIN_SCALE)).coerceAtLeast(0f))

    /** Scrolls to [fraction] of the document height (fast-scroll, F-NAV-3). Stop flings first. */
    fun scrollToFraction(fraction: Float) {
        offsetY = -fraction.coerceIn(0f, 1f) * scrollRangePx
        clampOffsets()
    }

    /** Puts the top of page [index] at the top of the viewport, cancelling any fling. */
    suspend fun scrollToPage(index: Int) = mutex.mutate(MutatePriority.UserInput) {
        offsetY = -layout.pageRect(index.coerceIn(0, layout.pageCount - 1)).top * scale
        clampOffsets()
    }

    /**
     * Returns the current visible rectangle in document point coordinates.
     */
    fun getVisibleRectPoints(): ViewportRect {
        if (scale <= 0f) return ViewportRect(0f, 0f, layout.width, layout.height)

        val left = (-offsetX / scale).coerceIn(0f, layout.width)
        val top = (-offsetY / scale).coerceIn(0f, layout.height)
        val right = ((-offsetX + viewportWidth) / scale).coerceIn(0f, layout.width)
        val bottom = ((-offsetY + viewportHeight) / scale).coerceIn(0f, layout.height)

        return ViewportRect(left = left, top = top, right = right, bottom = bottom)
    }

    /** Scrolls vertically by [deltaYPx] pixels (positive scrolls down). */
    fun scrollBy(deltaYPx: Float) {
        onPan(0f, -deltaYPx)
    }

    /** Scrolls vertically by a viewport page fraction (e.g. 0.9 for PageUp/PageDown). */
    fun scrollPage(down: Boolean, fraction: Float = 0.9f) {
        val delta = viewportHeight * fraction
        onPan(0f, if (down) -delta else delta)
    }

    /** Scrolls to top of document (Home key). */
    fun scrollToTop() {
        scrollToFraction(0f)
    }

    /** Scrolls to bottom of document (End key). */
    fun scrollToBottom() {
        scrollToFraction(1f)
    }

    /** Zooms in anchored at the viewport center (Ctrl + '+'). */
    fun zoomIn(factor: Float = 1.25f) {
        val center = Offset(viewportWidth / 2f, viewportHeight / 2f)
        onPinch(factor, center)
    }

    /** Zooms out anchored at the viewport center (Ctrl + '-'). */
    fun zoomOut(factor: Float = 1.25f) {
        val center = Offset(viewportWidth / 2f, viewportHeight / 2f)
        onPinch(1f / factor, center)
    }

    /** Resets zoom to fit-width scale (Ctrl + '0'). */
    fun resetZoom() {
        val center = Offset(viewportWidth / 2f, viewportHeight / 2f)
        if (scale > 0f) {
            onPinch(fitWidthScale / scale, center)
        }
    }
}

private const val MIN_SCALE = 1e-3f

@Composable
fun rememberDocumentTransformState(
    layout: DocumentLayout
): DocumentTransformState {
    return remember(layout) {
        DocumentTransformState(layout)
    }
}
