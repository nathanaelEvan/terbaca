package app.terbaca.feature.slides

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Max distance between the two taps of a double tap (Android's `ViewConfiguration` default). */
private val DOUBLE_TAP_SLOP = 100.dp

/**
 * Pinch, zoomed pan, fling and double tap on the current slide (F-NAV-1).
 *
 * A one-finger drag at fit is left unconsumed for the pager to swipe. Two fingers zoom; once
 * zoomed, the pager's swiping is off (see [SlideZoomState.isZoomed]) and one finger pans.
 * Only the slide where [isActive] is true reacts; [contentSize] is its fitted size in px.
 */
internal fun Modifier.slideZoomGestures(
    zoomState: SlideZoomState,
    isActive: () -> Boolean,
    contentSize: () -> Pair<Int, Int>,
    coroutineScope: CoroutineScope,
    decay: DecayAnimationSpec<Float>
): Modifier = pointerInput(zoomState) {
    val velocityTracker = SlidePanVelocityTracker()
    var animationJob: Job? = null
    var lastTapUptime = 0L
    var lastTapPosition = Offset.Zero

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!isActive()) return@awaitEachGesture
        animationJob?.cancel()
        animationJob = null
        zoomState.isInteracting = false
        val (contentWidth, contentHeight) = contentSize()
        zoomState.setGeometry(size.width.toFloat(), size.height.toFloat(), contentWidth.toFloat(), contentHeight.toFloat())
        velocityTracker.reset(down.uptimeMillis)

        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        var multiTouch = false
        var consumedElsewhere = false
        var upUptime = down.uptimeMillis
        val touchSlop = viewConfiguration.touchSlop

        try {
            do {
                val event = awaitPointerEvent()
                upUptime = event.changes.maxOf { it.uptimeMillis }
                val pressed = event.changes.count { it.pressed }
                if (pressed > 1) multiTouch = true
                if (event.changes.any { it.isConsumed }) {
                    // The pager took this drag.
                    consumedElsewhere = true
                    continue
                }
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val pinching = pressed > 1 && (zoom < 0.95f || zoom > 1.05f || pan.getDistance() > touchSlop)
                    val panningZoomed = zoomState.isZoomed && pan.getDistance() > touchSlop
                    if (pinching || panningZoomed) {
                        pastTouchSlop = true
                        zoomState.isInteracting = true
                    }
                }
                if (pastTouchSlop) {
                    val centroid = event.calculateCentroid()
                    if (centroid != Offset.Unspecified) {
                        if (zoomChange != 1f) zoomState.onPinch(zoomChange, centroid.x, centroid.y)
                        if (panChange != Offset.Zero) zoomState.onPan(panChange.x, panChange.y)
                    }
                    event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                    velocityTracker.add(upUptime, panChange, pressed)
                }
            } while (event.changes.any { it.pressed })
        } finally {
            zoomState.isInteracting = false
        }

        when {
            pastTouchSlop && zoomState.isZoomed -> {
                val velocity = velocityTracker.velocity()
                animationJob = launchInteraction(coroutineScope, zoomState, { animationJob }) {
                    zoomState.fling(velocity.x, velocity.y, decay)
                }
            }
            // A tap: one finger that never moved past the slop (a pager swipe moves further).
            !pastTouchSlop && !multiTouch && !consumedElsewhere && pan.getDistance() <= touchSlop -> {
                val isDoubleTap = upUptime - lastTapUptime <= viewConfiguration.doubleTapTimeoutMillis &&
                    (down.position - lastTapPosition).getDistance() <= DOUBLE_TAP_SLOP.toPx()
                if (isDoubleTap) {
                    lastTapUptime = 0L
                    animationJob = launchInteraction(coroutineScope, zoomState, { animationJob }) {
                        zoomState.animateDoubleTap(down.position.x, down.position.y)
                    }
                } else {
                    lastTapUptime = upUptime
                    lastTapPosition = down.position
                }
            }
        }
    }
}

/**
 * Runs a fling or double-tap animation as an interaction, so the sharp re-render waits for it.
 * Only the latest animation ([currentJob]) clears the flag: a cancelled one finishing late must
 * not mark a newer gesture as settled.
 */
private fun launchInteraction(
    coroutineScope: CoroutineScope,
    zoomState: SlideZoomState,
    currentJob: () -> Job?,
    block: suspend () -> Unit
): Job {
    zoomState.isInteracting = true
    lateinit var job: Job
    job = coroutineScope.launch {
        try {
            block()
        } finally {
            if (currentJob() === job) zoomState.isInteracting = false
        }
    }
    return job
}
