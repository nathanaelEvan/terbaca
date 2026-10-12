package app.terbaca.feature.reader

import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.pyramid.TilePyramid
import app.terbaca.core.render.pyramid.ViewportRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchBaseLayerTest {

    private val pages = listOf(
        PageInfo(index = 0, widthPoints = 600f, heightPoints = 800f),
        PageInfo(index = 1, widthPoints = 600f, heightPoints = 800f),
        PageInfo(index = 2, widthPoints = 600f, heightPoints = 800f)
    )
    private val layout = DocumentLayout(pages, gapPoints = 10f)

    @Test
    fun zooming_returnsBaseKeysOfVisiblePagesAndNoDetailKeys() {
        // Viewport viewing pages 0 and 1
        val visibleRect = ViewportRect(left = 0f, top = 0f, right = 600f, bottom = 1200f)

        val plan = computeTilePlan(
            layout = layout,
            visibleRect = visibleRect,
            scale = 1.0f,
            offsetX = 0f,
            offsetY = 0f,
            fitWidthScale = 1.0f,
            levelScale = 1.0f,
            viewportWidth = 600f,
            viewportHeight = 1200f,
            zooming = true
        )

        val expectedBaseKeys = setOf(
            TilePyramid.computeBaseLevelBounds(pages[0], 1.0f).key,
            TilePyramid.computeBaseLevelBounds(pages[1], 1.0f).key
        )

        // Must request base keys of visible pages mid-pinch
        assertEquals(expectedBaseKeys, plan.visibleBases.map { it.key }.toSet())
        assertTrue("Base keys must be pinned hot during pinch", plan.activeKeys.containsAll(expectedBaseKeys))

        // Detail and prefetch must NOT be requested mid-pinch (detail level stays frozen)
        assertTrue("Detail tiles must not be requested mid-pinch", plan.visibleDetail.isEmpty())
        assertTrue("Prefetch tiles must not be requested mid-pinch", plan.prefetch.isEmpty())
    }

    @Test
    fun notZooming_returnsBothBaseAndDetailKeys() {
        val visibleRect = ViewportRect(left = 0f, top = 0f, right = 600f, bottom = 1200f)

        val plan = computeTilePlan(
            layout = layout,
            visibleRect = visibleRect,
            scale = 1.0f,
            offsetX = 0f,
            offsetY = 0f,
            fitWidthScale = 1.0f,
            levelScale = 1.0f,
            viewportWidth = 600f,
            viewportHeight = 1200f,
            zooming = false
        )

        val expectedBaseKeys = setOf(
            TilePyramid.computeBaseLevelBounds(pages[0], 1.0f).key,
            TilePyramid.computeBaseLevelBounds(pages[1], 1.0f).key
        )

        assertEquals(expectedBaseKeys, plan.visibleBases.map { it.key }.toSet())
        assertTrue("Detail tiles must be requested when not zooming", plan.visibleDetail.isNotEmpty())
    }
}
