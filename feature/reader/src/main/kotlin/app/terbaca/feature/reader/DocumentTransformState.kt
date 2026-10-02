package app.terbaca.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

    private val animOffsetX = Animatable(0f)
    private val animOffsetY = Animatable(0f)

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
    ) = coroutineScope {
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
}

@Composable
fun rememberDocumentTransformState(
    layout: DocumentLayout
): DocumentTransformState {
    return remember(layout) {
        DocumentTransformState(layout)
    }
}
