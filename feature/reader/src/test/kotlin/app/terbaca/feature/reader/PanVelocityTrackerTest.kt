package app.terbaca.feature.reader

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PanVelocityTrackerTest {

    @Test
    fun oneFingerDrag_flingsInDragDirection() {
        val tracker = PanVelocityTracker()
        tracker.reset(0L)
        for (t in 1..10) tracker.add(t * 8L, Offset(0f, -20f), pressedPointers = 1)
        // 20 px per 8 ms upward = -2500 px/s.
        assertEquals(-2500f, tracker.velocity().y, 250f)
    }

    @Test
    fun pinchThenRelease_doesNotFling() {
        val tracker = PanVelocityTracker()
        tracker.reset(0L)
        // Second finger lands; symmetric pinch: centroid barely moves.
        for (t in 1..10) tracker.add(t * 8L, Offset(0f, if (t % 2 == 0) 0.5f else -0.5f), pressedPointers = 2)
        // Fingers lift one at a time; the last one drifts while lifting.
        tracker.add(88L, Offset(0f, 30f), pressedPointers = 1)
        tracker.add(96L, Offset.Zero, pressedPointers = 0)
        val v = tracker.velocity()
        assertTrue("unexpected fling $v", abs(v.x) < 50f && abs(v.y) < 50f)
    }

    @Test
    fun releaseEvent_addsNoSample() {
        val tracker = PanVelocityTracker()
        tracker.reset(0L)
        for (t in 1..10) tracker.add(t * 8L, Offset(20f, 0f), pressedPointers = 1)
        tracker.add(200L, Offset(5000f, 0f), pressedPointers = 0)
        assertEquals(2500f, tracker.velocity().x, 250f)
    }
}
