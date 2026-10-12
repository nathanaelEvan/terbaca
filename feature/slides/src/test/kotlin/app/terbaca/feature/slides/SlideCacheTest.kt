package app.terbaca.feature.slides

import app.terbaca.core.model.PageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideCacheTest {

    private data class TestItem(val id: Int)

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

    private fun slide(page: Int) = SlideKey(pageIndex = page, widthPx = 3200, heightPx = 1800)

    @Test
    fun slideCacheStoresAndRetrievesItems() {
        val cache = SlideCache<TestItem>(maxEntries = 3)
        val item0 = TestItem(0)
        cache.put(slide(0), item0)

        assertEquals(item0, cache.get(slide(0)))
        assertEquals(1, cache.size())
    }

    @Test
    fun slideCacheEvictsTheSlideFarthestFromTheNewOne() {
        val cache = SlideCache<TestItem>(maxEntries = 2)
        cache.put(slide(5), TestItem(5))
        cache.put(slide(1), TestItem(1))

        // Read 1 last: recency must not matter, only distance from the new slide.
        cache.get(slide(1))
        cache.put(slide(6), TestItem(6))

        assertNull(cache.get(slide(1)))
        assertEquals(TestItem(5), cache.get(slide(5)))
        assertEquals(TestItem(6), cache.get(slide(6)))
    }

    /**
     * Replays the cache calls the Pad 7 trace showed for SlideFlipBenchmark (M6R-05): flicking
     * forward, the pager composes slides up to two ahead; flicking back, up to two behind. A newly
     * composed slide reads the cache and renders on a miss; nothing else reads it until arrival.
     * With least-recently-used eviction this gave SSNSSNSS NSSNSSSS, as on the device.
     */
    @Test
    fun everyFlipArrivesOnACachedSlide() {
        val cache = SlideCache<TestItem>()
        var composed = setOf(0, 1)
        composed.forEach { cache.put(slide(it), TestItem(it)) }
        var current = 0
        val arrivals = StringBuilder()
        for (step in List(8) { 1 } + List(8) { -1 }) {
            val target = current + step
            val window = if (step > 0) target - 1..target + 2 else target - 2..target + 1
            val nowComposed = window.filter { it >= 0 }.toSet()
            for (page in (nowComposed - composed).sorted()) {
                if (cache.get(slide(page)) == null) cache.put(slide(page), TestItem(page))
            }
            composed = nowComposed
            current = target
            arrivals.append(if (cache.get(slide(target)) != null) 'S' else 'N')
        }
        assertEquals("S".repeat(16), arrivals.toString())
    }

    @Test
    fun slideCacheCapacityCoversComposedPagerWindow() {
        // The current slide plus SLIDE_BEYOND_VIEWPORT_PAGES on each side are composed at once.
        assertTrue(SLIDE_CACHE_ENTRIES >= 2 * SLIDE_BEYOND_VIEWPORT_PAGES + 1)
    }

    @Test
    fun slideCacheMissesWhenRenderSizeChanges() {
        val cache = SlideCache<TestItem>()
        val beforeRotation = TestItem(0)
        cache.put(SlideKey(pageIndex = 0, widthPx = 1600, heightPx = 900), beforeRotation)

        // After a rotation or resize the same page needs a new render, not an upscaled bitmap.
        assertNull(cache.get(SlideKey(pageIndex = 0, widthPx = 2560, heightPx = 1440)))
        assertEquals(beforeRotation, cache.get(SlideKey(pageIndex = 0, widthPx = 1600, heightPx = 900)))
    }

    @Test
    fun slideCacheClearDropsAllEntries() {
        val cache = SlideCache<TestItem>(maxEntries = 3)
        cache.put(slide(0), TestItem(0))
        cache.put(slide(1), TestItem(1))

        cache.clear()
        assertNull(cache.get(slide(0)))
        assertEquals(0, cache.size())
    }

    @Test
    fun slideSettleDetectorTriggersHapticAndCallbackOnlyOnSettle() {
        var hapticCount = 0
        var settledPage = -1

        val detector = SlideSettleDetector(
            initialPage = 0,
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

    @Test
    fun slideSettleDetectorIgnoresDragThatSnapsBackToSamePage() {
        var hapticCount = 0
        val settled = mutableListOf<Int>()
        val detector = SlideSettleDetector(
            initialPage = 4,
            onPageSettled = { settled += it },
            onHapticTick = { hapticCount++ }
        )

        // A drag on slide 4 that snaps back to slide 4 fires nothing
        detector.onScrollStateChanged(scrolling = true, settledPage = 4)
        detector.onScrollStateChanged(scrolling = false, settledPage = 4)
        assertEquals(0, hapticCount)
        assertTrue(settled.isEmpty())

        // A real flip to 5 still ticks, and a later snap-back on 5 doesn't
        detector.onScrollStateChanged(scrolling = true, settledPage = 4)
        detector.onScrollStateChanged(scrolling = false, settledPage = 5)
        detector.onScrollStateChanged(scrolling = true, settledPage = 5)
        detector.onScrollStateChanged(scrolling = false, settledPage = 5)
        assertEquals(1, hapticCount)
        assertEquals(listOf(5), settled)
    }

    @Test
    fun checkSlideSharpOnArrivalMarksSharpWhenSlideIsInCache() {
        val cache = SlideCache<TestItem>()
        val page = PageInfo(index = 2, widthPoints = 960f, heightPoints = 540f)
        val (w, h) = calculateAspectFit(1920, 1080, 960f, 540f)
        cache.put(SlideKey(pageIndex = 2, widthPx = w, heightPx = h), TestItem(2))

        var sharpMarked = false
        var notSharpMarked = false
        val result = checkSlideSharpOnArrival(
            slideCache = cache,
            page = page,
            viewportWidth = 1920,
            viewportHeight = 1080,
            onSharp = { sharpMarked = true },
            onNotSharp = { notSharpMarked = true },
            isSharp = { true }
        )

        assertTrue(result)
        assertTrue(sharpMarked)
        assertFalse(notSharpMarked)
    }

    @Test
    fun checkSlideSharpOnArrivalMarksNotSharpWhenSlideNotInCache() {
        val cache = SlideCache<TestItem>()
        val page = PageInfo(index = 3, widthPoints = 960f, heightPoints = 540f)

        var sharpMarked = false
        var notSharpMarked = false
        val result = checkSlideSharpOnArrival(
            slideCache = cache,
            page = page,
            viewportWidth = 1920,
            viewportHeight = 1080,
            onSharp = { sharpMarked = true },
            onNotSharp = { notSharpMarked = true },
            isSharp = { true }
        )

        assertFalse(result)
        assertFalse(sharpMarked)
        assertTrue(notSharpMarked)
    }

    @Test
    fun checkSlideSharpOnArrivalDoesNothingWhenViewportOrPageInvalid() {
        val cache = SlideCache<TestItem>()
        var marked = false

        val res1 = checkSlideSharpOnArrival(
            slideCache = cache,
            page = null,
            viewportWidth = 1920,
            viewportHeight = 1080,
            onSharp = { marked = true },
            onNotSharp = { marked = true }
        )
        assertFalse(res1)
        assertFalse(marked)

        val page = PageInfo(index = 0, widthPoints = 960f, heightPoints = 540f)
        val res2 = checkSlideSharpOnArrival(
            slideCache = cache,
            page = page,
            viewportWidth = 0,
            viewportHeight = 1080,
            onSharp = { marked = true },
            onNotSharp = { marked = true }
        )
        assertFalse(res2)
        assertFalse(marked)
    }
}
