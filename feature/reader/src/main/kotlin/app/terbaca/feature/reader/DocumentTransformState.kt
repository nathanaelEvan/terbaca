package app.terbaca.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A continuous-mode viewport saved per document (F-LIB-2), independent of screen size and density:
 * [zoom] is relative to fit-width (1 = fit-width), and ([x], [y]) is the document point (PDF
 * points in [DocumentLayout]) at the top-left of the viewport. It restores the same zoom and
 * reading position after a rotation, on another window size, or on another device.
 */
data class ReaderViewport(val zoom: Float, val x: Float, val y: Float)

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
    initialViewportHeight: Float = 0f,
    val initialPage: Int = 0,
    /** Restored on the first valid viewport instead of [initialPage], when set. */
    val initialViewport: ReaderViewport? = null
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

    /** The running fling's job, so synchronous actions (keyboard) can stop it first. */
    private var flingJob: Job? = null
    private var hasAppliedInitialPage = false

    /** False until the first valid viewport sets the scale to fit-width. */
    private var isScaleInitialized = false

    init {
        if (initialViewportWidth > 0f && initialViewportHeight > 0f) {
            updateViewport(initialViewportWidth, initialViewportHeight)
        }
    }

    /**
     * Updates viewport dimensions and recalculates fit scales and bounds.
     *
     * On a resize (docked sidebar, rotation) a view at fit-width stays at fit-width, and the
     * document point at the top-left of the viewport stays there, so the reading position holds.
     */
    fun updateViewport(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val wasAtFitWidth = isScaleInitialized &&
            abs(scale - fitWidthScale) <= fitWidthScale * FIT_WIDTH_EPSILON
        val anchorX = (-offsetX / scale).coerceAtLeast(0f)
        val anchorY = (-offsetY / scale).coerceAtLeast(0f)
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

        if (!isScaleInitialized || wasAtFitWidth) scale = fitWidthScale
        scale = scale.coerceIn(minScale, maxScale)

        if (!isScaleInitialized) {
            isScaleInitialized = true
        } else {
            offsetX = -anchorX * scale
            offsetY = -anchorY * scale
        }

        if (!hasAppliedInitialPage) {
            hasAppliedInitialPage = true
            if (initialViewport != null) {
                scale = (fitWidthScale * initialViewport.zoom).coerceIn(minScale, maxScale)
                offsetX = -initialViewport.x * scale
                offsetY = -initialViewport.y * scale
            } else if (initialPage in 0 until layout.pageCount) {
                offsetY = -layout.pageRect(initialPage).top * scale
            }
        }

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
     * Handles pinch zoom by [zoomRatio] anchored around [focalPoint], optionally applying simultaneous [pan].
     *
     * Anchoring formula (PRD §4.6):
     * The point in document coordinates under [focalPoint] remains under [focalPoint] after scaling:
     * offset_new = focal - (focal - offset_old) * (scale_new / scale_old) + pan
     */
    fun onPinch(zoomRatio: Float, focalPoint: Offset, pan: Offset = Offset.Zero) {
        if (scale <= 0f) return
        if (zoomRatio == 1f && pan == Offset.Zero) return

        val oldScale = scale
        val targetScale = (scale * zoomRatio).coerceIn(minScale, maxScale)
        if (targetScale == oldScale && pan == Offset.Zero) return

        val actualRatio = if (oldScale > 0f) targetScale / oldScale else 1f
        val fx = focalPoint.x
        val fy = focalPoint.y

        offsetX = fx - (fx - offsetX) * actualRatio + pan.x
        offsetY = fy - (fy - offsetY) * actualRatio + pan.y
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
            val job = coroutineContext[Job]
            flingJob = job
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
            try {
                jobX.join()
                jobY.join()
            } finally {
                if (flingJob === job) flingJob = null
            }
        }
    }

    /**
     * Stops a running fling right away, so a discrete action (a key press) isn't overwritten by
     * the fling's next frame. Cancelling also unregisters the fling's pending frame callback.
     */
    fun cancelFling() {
        flingJob?.cancel()
        flingJob = null
    }

    /** Cancels a running fling, e.g. when the user grabs the scrollbar. */
    suspend fun stopFling() = mutex.mutate(MutatePriority.UserInput) {}

    /** True once a real viewport size is known; before that the position means nothing. */
    val hasViewport: Boolean get() = viewportWidth > 0f && viewportHeight > 0f

    /** The current zoom and position, to save and restore later (F-LIB-2). Read outside composition. */
    val viewport: ReaderViewport
        get() = ReaderViewport(
            zoom = if (fitWidthScale > 0f) scale / fitWidthScale else 1f,
            x = -offsetX / max(scale, MIN_SCALE),
            y = -offsetY / max(scale, MIN_SCALE)
        )

    /** Scrollable height beyond the viewport, in pixels (0 when the document fits). */
    private val scrollRangePx: Float get() = max(0f, layout.height * scale - viewportHeight)

    /** Vertical scroll position in [0, 1]; 0 when the document fits on screen. */
    val scrollFraction: Float
        get() = if (scrollRangePx > 0f) (-offsetY / scrollRangePx).coerceIn(0f, 1f) else 0f

    /**
     * Current page (F-NAV-2): the first page at the scroll top, the last at the scroll bottom (so a
     * document of short pages still reaches N/N), otherwise the page filling most of the viewport.
     * The viewport centre was wrong when pages are shorter than half the viewport.
     */
    val currentPage: Int
        get() {
            val s = max(scale, MIN_SCALE)
            if (offsetY >= -SCROLL_EDGE_EPSILON_PX) return 0
            if (offsetY <= viewportHeight - layout.height * s + SCROLL_EDGE_EPSILON_PX) return layout.pageCount - 1
            val top = -offsetY / s
            val bottom = (viewportHeight - offsetY) / s
            var best = layout.pageAt(top)
            var bestShare = -1f
            for (index in layout.pagesIn(ViewportRect(0f, top, layout.width, bottom))) {
                val rect = layout.pageRect(index)
                val share = min(bottom, rect.bottom) - max(top, rect.top)
                if (share > bestShare) {
                    best = index
                    bestShare = share
                }
            }
            return best
        }

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
     * Jumps to a link or outline destination (F-NAV-5): page [index], with the destination's
     * [pageY] (points from the page top) a little below the viewport top when given. The zoom and
     * horizontal position stay, so a reader zoomed into a column keeps reading at that zoom.
     * [topClearancePx] keeps the destination below overlays at the viewport top (the back chip).
     */
    suspend fun scrollToDestination(index: Int, pageY: Float? = null, topClearancePx: Float = 0f) =
        mutex.mutate(MutatePriority.UserInput) {
            val rect = layout.pageRect(index.coerceIn(0, layout.pageCount - 1))
            val y = pageY?.takeIf { it.isFinite() }?.coerceIn(0f, rect.height)
                ?.let { rect.top + it - DESTINATION_MARGIN_POINTS }
                ?: rect.top
            offsetY = -max(0f, y) * scale + topClearancePx
            clampOffsets()
        }

    /** Returns to a saved [viewport] (the back chip, F-NAV-5), cancelling any fling. */
    suspend fun restoreViewport(viewport: ReaderViewport, animated: Boolean = false) =
        mutex.mutate(MutatePriority.UserInput) {
            val targetScale = (fitWidthScale * viewport.zoom).coerceIn(minScale, maxScale)
            moveTo(targetScale, -viewport.x * targetScale, -viewport.y * targetScale, animated)
        }

    /**
     * Column-aware double-tap zoom (F-ACAD-1): zooms so the span [left]..[right] (page points) of
     * page [pageIndex] fills the viewport width plus [marginPoints] on each side, keeping the tapped
     * page point [pageY] at screen y [screenY], so the line the user tapped stays under the finger.
     */
    suspend fun zoomToSpan(
        pageIndex: Int,
        left: Float,
        right: Float,
        pageY: Float,
        screenY: Float,
        marginPoints: Float = COLUMN_MARGIN_POINTS,
        animated: Boolean = true
    ) = mutex.mutate(MutatePriority.UserInput) {
        val rect = layout.pageRect(pageIndex.coerceIn(0, layout.pageCount - 1))
        val spanLeft = rect.left + left - marginPoints
        val spanWidth = max(1f, right - left + 2 * marginPoints)
        val targetScale = (viewportWidth / spanWidth).coerceIn(minScale, maxScale)
        moveTo(targetScale, -spanLeft * targetScale, screenY - (rect.top + pageY) * targetScale, animated)
    }

    /** Moves to [targetScale] and the clamped target offsets, animated or at once. Run inside [mutex]. */
    private suspend fun moveTo(targetScale: Float, targetX: Float, targetY: Float, animated: Boolean) {
        val startScale = scale
        val startX = offsetX
        val startY = offsetY
        scale = targetScale
        offsetX = targetX
        offsetY = targetY
        clampOffsets()
        if (!animated) return
        val endX = offsetX
        val endY = offsetY
        scale = startScale
        offsetX = startX
        offsetY = startY
        animate(0f, 1f, animationSpec = tween(DOUBLE_TAP_ZOOM_MILLIS)) { t, _ ->
            scale = startScale + (targetScale - startScale) * t
            offsetX = startX + (endX - startX) * t
            offsetY = startY + (endY - startY) * t
        }
        scale = targetScale
        offsetX = endX
        offsetY = endY
    }

    /**
     * The page under screen point [position] (pixels in the surface) and the point on that page in
     * page points from its top-left corner, or null in the gaps and margins around pages.
     */
    fun pagePointAt(position: Offset): PagePoint? {
        if (scale <= 0f) return null
        val docX = (position.x - offsetX) / scale
        val docY = (position.y - offsetY) / scale
        if (docY < 0f || docY > layout.height) return null
        // A spread row holds two pages side by side (F-MODE-2).
        for (index in layout.rowPagesAt(docY)) {
            val rect = layout.pageRect(index)
            if (docX >= rect.left && docX <= rect.right && docY >= rect.top && docY <= rect.bottom) {
                return PagePoint(index, docX - rect.left, docY - rect.top)
            }
        }
        return null
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

    // Keyboard actions (F-NAV-7) stop a running fling first, or its next frame would undo them.

    /** Scrolls vertically by [deltaYPx] pixels (positive scrolls down). */
    fun scrollBy(deltaYPx: Float) {
        cancelFling()
        onPan(0f, -deltaYPx)
    }

    /** Scrolls horizontally by [deltaXPx] pixels (positive scrolls right). */
    fun scrollHorizontallyBy(deltaXPx: Float) {
        cancelFling()
        onPan(-deltaXPx, 0f)
    }

    /** Scrolls vertically by a viewport page fraction (e.g. 0.9 for PageUp/PageDown). */
    fun scrollPage(down: Boolean, fraction: Float = 0.9f) {
        cancelFling()
        val delta = viewportHeight * fraction
        onPan(0f, if (down) -delta else delta)
    }

    /** Scrolls to top of document (Home key). */
    fun scrollToTop() {
        cancelFling()
        scrollToFraction(0f)
    }

    /** Scrolls to bottom of document (End key). */
    fun scrollToBottom() {
        cancelFling()
        scrollToFraction(1f)
    }

    /** Zooms in anchored at the viewport center (Ctrl + '+'). */
    fun zoomIn(factor: Float = 1.25f) {
        cancelFling()
        val center = Offset(viewportWidth / 2f, viewportHeight / 2f)
        onPinch(factor, center)
    }

    /** Zooms out anchored at the viewport center (Ctrl + '-'). */
    fun zoomOut(factor: Float = 1.25f) {
        cancelFling()
        val center = Offset(viewportWidth / 2f, viewportHeight / 2f)
        onPinch(1f / factor, center)
    }

    /** Resets zoom to fit-width scale (Ctrl + '0'). */
    fun resetZoom() {
        cancelFling()
        val center = Offset(viewportWidth / 2f, viewportHeight / 2f)
        if (scale > 0f) {
            onPinch(fitWidthScale / scale, center)
        }
    }
}

/** A point on page [pageIndex], in page points from the page's top-left corner. */
data class PagePoint(val pageIndex: Int, val x: Float, val y: Float)

private const val MIN_SCALE = 1e-3f

/** A destination lands this far below the viewport top, so its heading isn't flush with the edge. */
private const val DESTINATION_MARGIN_POINTS = 12f

/** Space left on each side of a column zoomed to by a double-tap (F-ACAD-1). */
const val COLUMN_MARGIN_POINTS = 8f

private const val DOUBLE_TAP_ZOOM_MILLIS = 250

/** Relative tolerance for "the scale is at fit-width" when the viewport resizes. */
private const val FIT_WIDTH_EPSILON = 1e-3f

/** Within this many pixels of either scroll end counts as at that end. */
private const val SCROLL_EDGE_EPSILON_PX = 0.5f

@Composable
fun rememberDocumentTransformState(
    layout: DocumentLayout,
    initialPage: Int = 0,
    initialViewport: ReaderViewport? = null
): DocumentTransformState {
    return remember(layout) {
        DocumentTransformState(layout, initialPage = initialPage, initialViewport = initialViewport)
    }
}
