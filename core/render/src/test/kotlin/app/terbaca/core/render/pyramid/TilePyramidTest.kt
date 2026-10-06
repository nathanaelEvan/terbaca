package app.terbaca.core.render.pyramid

import app.terbaca.core.model.PageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TilePyramidTest {

    private val letterPage = PageInfo(
        index = 0,
        widthPoints = 612f,
        heightPoints = 792f
    )

    @Test
    fun testBuildLevels_discretePowersOfSqrtTwo() {
        val viewportWidth = 1080f
        val viewportHeight = 1920f
        val levels = TilePyramid.buildLevels(letterPage, viewportWidth, viewportHeight)

        assertTrue("Levels must not be empty", levels.isNotEmpty())
        assertEquals(0, levels.first().levelIndex)

        val fitWidthScale = viewportWidth / letterPage.widthPoints
        val fitPageScale = minOf(viewportWidth / letterPage.widthPoints, viewportHeight / letterPage.heightPoints)

        assertEquals("Level 0 must be fit-page scale", fitPageScale, levels.first().scale, 0.01f)

        // Verify each step is sqrt(2)
        for (i in 0 until levels.size - 1) {
            val ratio = levels[i + 1].scale / levels[i].scale
            assertEquals(
                "Step between levels must be sqrt(2)",
                TilePyramid.ZOOM_STEP_RATIO,
                ratio,
                0.01f
            )
        }

        // Verify highest level reaches at least 8x fit-width scale
        val maxTargetScale = fitWidthScale * TilePyramid.DEFAULT_MAX_ZOOM_MULTIPLIER
        assertTrue(
            "Max level scale (${levels.last().scale}) must cover 8x fit-width ($maxTargetScale)",
            levels.last().scale >= maxTargetScale - 1e-3f
        )
    }

    @Test
    fun testSelectLevel_atOrAboveCurrentZoom() {
        val levels = TilePyramid.buildLevels(letterPage, 1000f, 1000f)

        // Exactly on level 1
        val level1 = levels[1]
        val selectedExact = TilePyramid.selectLevel(levels, level1.scale)
        assertEquals(1, selectedExact.levelIndex)

        // Slightly above level 1 should pick level 2 (next level at or above)
        val selectedAbove = TilePyramid.selectLevel(levels, level1.scale + 0.05f)
        assertEquals(2, selectedAbove.levelIndex)

        // Scale below level 0 clamps to level 0
        val selectedBelow = TilePyramid.selectLevel(levels, 0.01f)
        assertEquals(0, selectedBelow.levelIndex)

        // Scale above max level clamps to max level
        val selectedHigh = TilePyramid.selectLevel(levels, 999f)
        assertEquals(levels.last().levelIndex, selectedHigh.levelIndex)
    }

    @Test
    fun testTileBounds_andEdgeTileDimensions() {
        val levels = TilePyramid.buildLevels(letterPage, 1000f, 1000f, tileSize = 512)
        val highLevel = levels.first { it.cols > 1 && it.rows > 1 }

        // Origin tile (0, 0)
        val originTile = TilePyramid.computeTileBounds(letterPage, highLevel, col = 0, row = 0)
        assertEquals(0f, originTile.pageRect.left, 0.001f)
        assertEquals(0f, originTile.pageRect.top, 0.001f)
        assertEquals(512, originTile.pixelWidth)
        assertEquals(512, originTile.pixelHeight)

        // Edge tile (last col, last row) may have partial pixel dimension
        val lastCol = highLevel.cols - 1
        val lastRow = highLevel.rows - 1
        val edgeTile = TilePyramid.computeTileBounds(letterPage, highLevel, col = lastCol, row = lastRow)

        val expectedWidth = highLevel.totalPixelWidth - (lastCol * 512)
        val expectedHeight = highLevel.totalPixelHeight - (lastRow * 512)
        assertEquals(expectedWidth, edgeTile.pixelWidth)
        assertEquals(expectedHeight, edgeTile.pixelHeight)

        // Bounds in points should not exceed page dimensions
        assertTrue(edgeTile.pageRect.right <= letterPage.widthPoints + 1e-4f)
        assertTrue(edgeTile.pageRect.bottom <= letterPage.heightPoints + 1e-4f)
    }

    @Test
    fun testVisibleTiles_intersection() {
        val levels = TilePyramid.buildLevels(letterPage, 1000f, 1000f, tileSize = 512)
        val level = levels.first { it.cols >= 2 && it.rows >= 2 }

        // Viewport covering only the top-left corner
        val topLeftViewport = ViewportRect(0f, 0f, 100f, 100f)
        val visibleTiles = TilePyramid.getVisibleTiles(letterPage, level, topLeftViewport)

        assertEquals(1, visibleTiles.size)
        assertEquals(0, visibleTiles[0].key.col)
        assertEquals(0, visibleTiles[0].key.row)

        // Viewport covering entire page
        val fullViewport = ViewportRect(0f, 0f, letterPage.widthPoints, letterPage.heightPoints)
        val allTiles = TilePyramid.getVisibleTiles(letterPage, level, fullViewport)
        assertEquals(level.cols * level.rows, allTiles.size)
    }

    @Test
    fun testPrefetchRing_directionalAndOmnidirectional() {
        val levels = TilePyramid.buildLevels(letterPage, 2000f, 2000f, tileSize = 256)
        val level = levels.first { it.cols >= 4 && it.rows >= 4 }

        // Visible tiles: 2x2 grid in center (cols 1..2, rows 1..2)
        val visibleTiles = listOf(
            TilePyramid.computeTileBounds(letterPage, level, 1, 1),
            TilePyramid.computeTileBounds(letterPage, level, 2, 1),
            TilePyramid.computeTileBounds(letterPage, level, 1, 2),
            TilePyramid.computeTileBounds(letterPage, level, 2, 2)
        )

        // Downward scroll (deltaY > 0): should prefetch row 3 below
        val downPrefetch = TilePyramid.getPrefetchRing(
            letterPage, level, visibleTiles, scrollDeltaX = 0f, scrollDeltaY = 100f, ringDepth = 1
        )
        assertFalse(downPrefetch.isEmpty())
        assertTrue("All downward prefetch tiles must be row 3", downPrefetch.all { it.key.row == 3 })

        // Stationary (deltaX=0, deltaY=0): 1-tile border ring around the 2x2 box
        val omniPrefetch = TilePyramid.getPrefetchRing(
            letterPage, level, visibleTiles, scrollDeltaX = 0f, scrollDeltaY = 0f, ringDepth = 1
        )
        // 4x4 surrounding box has 16 tiles - 4 visible = 12 border tiles
        assertEquals(12, omniPrefetch.size)
        // Ensure no visible tiles are included in prefetch
        val visibleKeys = visibleTiles.map { it.key }.toSet()
        assertTrue(omniPrefetch.none { it.key in visibleKeys })
    }

    @Test
    fun testThumbnailAndBaseBounds() {
        val thumb = TilePyramid.computeThumbnailBounds(letterPage, targetWidthPx = 400)
        assertEquals(-1, thumb.key.level)
        assertEquals(400, thumb.pixelWidth)
        val expectedHeight = (792f / 612f * 400f).toInt()
        assertTrue(abs(thumb.pixelHeight - expectedHeight) <= 1)

        val fitWidthScale = 1000f / letterPage.widthPoints
        val base = TilePyramid.computeBaseLevelBounds(letterPage, fitWidthScale)
        assertEquals(-2, base.key.level)
        assertEquals(1000, base.pixelWidth)
        assertTrue(base.pixelHeight > 1000)
    }

    @Test
    fun testBaseBounds_cappedOnLargeScreens() {
        // A4 portrait at fit-width on a 3200 px wide tablet would be ~4528 px tall, over the
        // renderer's 4096 px limit.
        val a4 = PageInfo(index = 0, widthPoints = 595f, heightPoints = 842f)
        val base = TilePyramid.computeBaseLevelBounds(a4, fitWidthScale = 3200f / 595f)
        assertEquals(TilePyramid.MAX_BASE_DIMENSION, base.pixelHeight)
        val expectedWidth = 595f / 842f * TilePyramid.MAX_BASE_DIMENSION
        assertTrue(abs(base.pixelWidth - expectedWidth) <= 1f)
        assertEquals(base.pixelWidth, base.fullWidth)
        assertEquals(base.pixelHeight, base.fullHeight)
    }
}
