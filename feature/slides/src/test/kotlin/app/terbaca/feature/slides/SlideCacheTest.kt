package app.terbaca.feature.slides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideCacheTest {

    private class TestItem(val id: Int) {
        var isDisposed = false
        fun dispose() {
            isDisposed = true
        }
    }

    @Test
    fun overshootDampingRatioIsUnderCriticalDamping() {
        // Damping ratio < 1.0f guarantees overshoot on snap per PRD F-MODE-3
        assertTrue("Damping ratio should be > 0", SLIDE_SNAP_DAMPING_RATIO > 0f)
        assertTrue("Damping ratio should be < 1 for overshoot", SLIDE_SNAP_DAMPING_RATIO < 1f)
    }

    @Test
    fun calculateAspectFitScalesLandscapePageCorrectly() {
        // 16:9 page (960 x 540) inside 3:2 screen (3200 x 2136)
        // Scale is limited by width: 3200 / 960 = 3.3333...
        // Target: 3200 x 1800
        val (w, h) = calculateAspectFit(
            viewportWidth = 3200,
            viewportHeight = 2136,
            pageWidthPoints = 960f,
            pageHeightPoints = 540f
        )
        assertEquals(3200, w)
        assertEquals(1800, h)
    }

    @Test
    fun calculateAspectFitScalesPortraitPageCorrectly() {
        // US Letter portrait (612 x 792) inside 3:2 screen (3200 x 2136)
        // Scale is limited by height: 2136 / 792 = 2.6969...
        // Target: (612 * 2.6969) = 1651 x 2136
        val (w, h) = calculateAspectFit(
            viewportWidth = 3200,
            viewportHeight = 2136,
            pageWidthPoints = 612f,
            pageHeightPoints = 792f
        )
        assertEquals(1651, w)
        assertEquals(2136, h)
    }

    @Test
    fun calculateAspectFitHandlesZeroOrNegativeInputs() {
        val (w1, h1) = calculateAspectFit(0, 2136, 960f, 540f)
        assertEquals(0, w1)
        assertEquals(0, h1)

        val (w2, h2) = calculateAspectFit(3200, 0, 960f, 540f)
        assertEquals(0, w2)
        assertEquals(0, h2)

        val (w3, h3) = calculateAspectFit(3200, 2136, 0f, 540f)
        assertEquals(0, w3)
        assertEquals(0, h3)
    }

    @Test
    fun slideCacheStoresAndRetrievesItems() {
        val cache = SlideCache<TestItem>(
            maxEntries = 3,
            isDisposed = { it.isDisposed },
            disposer = { it.dispose() }
        )
        val item0 = TestItem(0)
        cache.put(0, item0)

        assertEquals(item0, cache.get(0))
        assertEquals(1, cache.size())
    }

    @Test
    fun slideCacheEvictsOldestWhenCapacityExceeded() {
        val cache = SlideCache<TestItem>(
            maxEntries = 2,
            isDisposed = { it.isDisposed },
            disposer = { it.dispose() }
        )
        val item0 = TestItem(0)
        val item1 = TestItem(1)
        val item2 = TestItem(2)

        cache.put(0, item0)
        cache.put(1, item1)
        assertFalse(item0.isDisposed)
        assertFalse(item1.isDisposed)

        cache.put(2, item2) // Should evict item0
        assertTrue(item0.isDisposed)
        assertNull(cache.get(0))
        assertEquals(item1, cache.get(1))
        assertEquals(item2, cache.get(2))
    }

    @Test
    fun slideCacheMaintainsAccessOrderOnGet() {
        val cache = SlideCache<TestItem>(
            maxEntries = 2,
            isDisposed = { it.isDisposed },
            disposer = { it.dispose() }
        )
        val item0 = TestItem(0)
        val item1 = TestItem(1)
        val item2 = TestItem(2)

        cache.put(0, item0)
        cache.put(1, item1)

        // Access 0 so 1 becomes the oldest
        assertEquals(item0, cache.get(0))

        cache.put(2, item2) // Should evict item1, keeping 0 and 2
        assertTrue(item1.isDisposed)
        assertFalse(item0.isDisposed)
        assertEquals(item0, cache.get(0))
        assertEquals(item2, cache.get(2))
    }

    @Test
    fun slideCacheDisposesAllOnClear() {
        val cache = SlideCache<TestItem>(
            maxEntries = 3,
            isDisposed = { it.isDisposed },
            disposer = { it.dispose() }
        )
        val item0 = TestItem(0)
        val item1 = TestItem(1)
        cache.put(0, item0)
        cache.put(1, item1)

        cache.clear()
        assertTrue(item0.isDisposed)
        assertTrue(item1.isDisposed)
        assertEquals(0, cache.size())
    }

    @Test
    fun slideSettleDetectorTriggersHapticAndCallbackOnlyOnSettle() {
        var hapticCount = 0
        var settledPage = -1

        val detector = SlideSettleDetector(
            onPageSettled = { settledPage = it },
            onHapticTick = { hapticCount++ }
        )

        // Initial state: not scrolling
        detector.onScrollStateChanged(scrolling = false, settledPage = 0)
        assertEquals(0, hapticCount)
        assertEquals(-1, settledPage)

        // Scroll starts
        detector.onScrollStateChanged(scrolling = true, settledPage = 0)
        assertEquals(0, hapticCount)
        assertEquals(-1, settledPage)

        // Scroll in progress continues
        detector.onScrollStateChanged(scrolling = true, settledPage = 1)
        assertEquals(0, hapticCount)
        assertEquals(-1, settledPage)

        // Scroll settles on page 1
        detector.onScrollStateChanged(scrolling = false, settledPage = 1)
        assertEquals(1, hapticCount)
        assertEquals(1, settledPage)

        // Repeated idle does not re-trigger
        detector.onScrollStateChanged(scrolling = false, settledPage = 1)
        assertEquals(1, hapticCount)
        assertEquals(1, settledPage)
    }
}
