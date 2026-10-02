package app.terbaca.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity

/**
 * Fling velocity from the gesture's pan (the fingers' centroid) only.
 *
 * Feeding every pointer into one [VelocityTracker] interleaves two fingers' positions during a
 * pinch, which reads as one finger jumping back and forth: a huge fake velocity that flung the
 * document away after zooming. Tracking restarts when the finger count changes, so lifting one
 * finger of a pinch is not counted as motion; release events (no finger down) add no sample.
 */
internal class PanVelocityTracker {
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
