package app.terbaca.feature.slides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideZoomTest {

    /** 1000 x 500 viewport with a 16:9-ish slide fitted to 800 x 450, centred. */
    private fun zoomState() = SlideZoomState().apply { setGeometry(1000f, 500f, 800f, 450f) }

    @Test
    fun startsAtFit_pagerUnlocked() {
        val zoom = zoomState()
        assertEquals(SLIDE_MIN_ZOOM, zoom.scale, 0f)
        assertFalse(zoom.isZoomed)
    }

    @Test
    fun pinch_clampsToFitAnd800Percent() {
        val zoom = zoomState()
        zoom.onPinch(100f, 500f, 250f)
        assertEquals(SLIDE_MAX_ZOOM, zoom.scale, 0f)

        zoom.onPinch(0.001f, 500f, 250f)
        assertEquals(SLIDE_MIN_ZOOM, zoom.scale, 0f)
        // Back at fit the slide is centred again.
        assertEquals(0f, zoom.offsetX, 0f)
        assertEquals(0f, zoom.offsetY, 0f)
    }

    @Test
    fun pinch_keepsSlidePointUnderFocusInPlace() {
        val zoom = zoomState()
        val focusX = 700f
        val focusY = 300f
        // Slide point under the focus at fit (screen = centre + (p - centre) * scale + offset).
        val pointX = 500f + (focusX - 500f - zoom.offsetX) / zoom.scale
        val pointY = 250f + (focusY - 250f - zoom.offsetY) / zoom.scale

        zoom.onPinch(2f, focusX, focusY)

        assertEquals(focusX, 500f + (pointX - 500f) * zoom.scale + zoom.offsetX, 0.01f)
        assertEquals(focusY, 250f + (pointY - 250f) * zoom.scale + zoom.offsetY, 0.01f)
    }

    @Test
    fun pan_isClampedToSlideEdges() {
        val zoom = zoomState()
        zoom.onPinch(2f, 500f, 250f)
        // Scaled slide 1600 x 900 in a 1000 x 500 viewport: 300 px and 200 px of travel each way.
        zoom.onPan(10_000f, -10_000f)
        assertEquals(300f, zoom.offsetX, 0.01f)
        assertEquals(-200f, zoom.offsetY, 0.01f)
    }

    @Test
    fun pan_atFitDoesNotMoveSlide() {
        val zoom = zoomState()
        zoom.onPan(200f, 100f)
        assertEquals(0f, zoom.offsetX, 0f)
        assertEquals(0f, zoom.offsetY, 0f)
    }

    @Test
    fun smallScaledAxis_staysCentred() {
        val zoom = zoomState()
        // 1.1x: 880 px wide still fits the 1000 px viewport, 495 px tall too.
        zoom.onPinch(1.1f, 900f, 450f)
        assertEquals(0f, zoom.offsetX, 0f)
        assertEquals(0f, zoom.offsetY, 0f)
    }

    @Test
    fun pagerLock_followsZoomedState() {
        val zoom = zoomState()
        zoom.onPinch(1.5f, 500f, 250f)
        assertTrue(zoom.isZoomed)

        zoom.reset()
        assertFalse(zoom.isZoomed)
        assertEquals(0f, zoom.offsetX, 0f)
    }

    @Test
    fun pagerLock_ignoresRoundingAboveFit() {
        assertFalse(isZoomedIn(1.005f))
        assertTrue(isZoomedIn(1.02f))
    }

    @Test
    fun doubleTap_zoomsInAtFitAndResetsWhenZoomed() {
        assertEquals(SLIDE_DOUBLE_TAP_ZOOM, doubleTapTargetZoom(1f), 0f)
        assertEquals(SLIDE_MIN_ZOOM, doubleTapTargetZoom(3f), 0f)
    }

    @Test
    fun maxOffset_isZeroWhileSlideFits() {
        assertEquals(0f, maxOffset(800f, 1000f, 1f), 0f)
        assertEquals(300f, maxOffset(800f, 1000f, 2f), 0f)
    }

    @Test
    fun detailRegion_nullAtFit() {
        assertNull(visibleDetailRegion(1000, 500, 1000, 500, 1f, 0f, 0f))
    }

    @Test
    fun detailRegion_coversVisibleCentreAtZoom() {
        val region = visibleDetailRegion(1000, 500, 1000, 500, 2f, 0f, 0f)
        assertEquals(SlideDetailRegion(2000, 1000, 500, 250, 1000, 500), region)
    }

    @Test
    fun detailRegion_followsPan() {
        // Panned fully right: the slide's left edge sits at the viewport's left edge.
        val region = visibleDetailRegion(1000, 500, 1000, 500, 2f, 500f, 0f)
        assertEquals(SlideDetailRegion(2000, 1000, 0, 250, 1000, 500), region)
    }

    @Test
    fun detailRegion_clipsToSlideWhenSlideIsSmallerThanViewport() {
        // 800 x 450 slide at 1.1x: 880 x 495, entirely on screen.
        val region = visibleDetailRegion(1000, 500, 800, 450, 1.1f, 0f, 0f)
        assertEquals(SlideDetailRegion(880, 495, 0, 0, 880, 495), region)
    }

    @Test
    fun detailRegion_capsTileSize() {
        val region = visibleDetailRegion(5000, 100, 5000, 100, 2f, 0f, 0f)!!
        assertEquals(SLIDE_MAX_DETAIL_TILE_PX, region.width)
        assertEquals(100, region.height)
    }
}
