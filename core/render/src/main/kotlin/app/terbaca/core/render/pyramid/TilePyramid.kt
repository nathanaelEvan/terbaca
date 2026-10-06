package app.terbaca.core.render.pyramid

import app.terbaca.core.model.PageInfo
import app.terbaca.core.model.TileKey
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometric model and level generator for the PDF tile pyramid per PRD §4.4.
 *
 * Rules:
 * - Coordinates: device pixels per PDF point (scale).
 * - Discrete zoom levels: powers of sqrt(2) from fit-page scale up to 8x fit-width scale.
 * - Level selection: for a given zoom, select tiles from the next level at or above the current zoom.
 * - Tile size: 512x512 device pixels by default (tunable constant).
 */
object TilePyramid {

    const val DEFAULT_TILE_SIZE = 512
    const val DEFAULT_MAX_ZOOM_MULTIPLIER = 8f

    /** Long-side cap for a page's base layer, in pixels. */
    const val MAX_BASE_DIMENSION = 2048
    val ZOOM_STEP_RATIO: Float = sqrt(2.0).toFloat()

    /**
     * Builds discrete pyramid levels from fit-page scale up to [maxZoomMultiplier] * fit-width scale.
     */
    fun buildLevels(
        pageInfo: PageInfo,
        viewportWidth: Float,
        viewportHeight: Float,
        tileSize: Int = DEFAULT_TILE_SIZE,
        maxZoomMultiplier: Float = DEFAULT_MAX_ZOOM_MULTIPLIER
    ): List<PyramidLevel> {
        require(pageInfo.widthPoints > 0f && pageInfo.heightPoints > 0f) { "Invalid page dimensions" }
        require(viewportWidth > 0f && viewportHeight > 0f) { "Invalid viewport dimensions" }
        require(tileSize > 0) { "Tile size must be positive" }

        val fitWidthScale = viewportWidth / pageInfo.widthPoints
        val fitHeightScale = viewportHeight / pageInfo.heightPoints
        val fitPageScale = min(fitWidthScale, fitHeightScale)
        val maxScale = fitWidthScale * maxZoomMultiplier

        val levels = mutableListOf<PyramidLevel>()
        var currentScale = fitPageScale
        var levelIndex = 0

        while (true) {
            val totalPixelWidth = ceil(pageInfo.widthPoints * currentScale).toInt()
            val totalPixelHeight = ceil(pageInfo.heightPoints * currentScale).toInt()
            val cols = max(1, ceil(totalPixelWidth.toDouble() / tileSize).toInt())
            val rows = max(1, ceil(totalPixelHeight.toDouble() / tileSize).toInt())

            levels.add(
                PyramidLevel(
                    levelIndex = levelIndex,
                    scale = currentScale,
                    relativeZoom = currentScale / fitWidthScale,
                    cols = cols,
                    rows = rows,
                    totalPixelWidth = totalPixelWidth,
                    totalPixelHeight = totalPixelHeight,
                    tileSize = tileSize
                )
            )

            if (currentScale >= maxScale - 1e-4f) {
                break
            }

            currentScale *= ZOOM_STEP_RATIO
            levelIndex++
        }

        return levels
    }

    /**
     * Selects the target level from [levels] for a given [currentScale].
     * Per PRD §4.4: "For a given zoom, use tiles from the next level at or above the current zoom;
     * the GPU downscales slightly, which stays sharp."
     */
    fun selectLevel(levels: List<PyramidLevel>, currentScale: Float): PyramidLevel {
        require(levels.isNotEmpty()) { "Levels must not be empty" }
        // Find the smallest level whose scale is >= currentScale
        for (level in levels) {
            if (level.scale >= currentScale - 1e-4f) {
                return level
            }
        }
        // If currentScale exceeds the maximum level, clamp to the highest level
        return levels.last()
    }

    /**
     * Computes the point bounding box and exact pixel dimensions of a specific tile.
     */
    fun computeTileBounds(
        pageInfo: PageInfo,
        level: PyramidLevel,
        col: Int,
        row: Int
    ): TileBounds {
        require(col in 0 until level.cols) { "col ($col) out of bounds [0, ${level.cols})" }
        require(row in 0 until level.rows) { "row ($row) out of bounds [0, ${level.rows})" }

        val startX = col * level.tileSize
        val startY = row * level.tileSize
        val pixelWidth = min(level.tileSize, level.totalPixelWidth - startX)
        val pixelHeight = min(level.tileSize, level.totalPixelHeight - startY)

        val leftPts = startX / level.scale
        val topPts = startY / level.scale
        val rightPts = min(pageInfo.widthPoints, (startX + pixelWidth) / level.scale)
        val bottomPts = min(pageInfo.heightPoints, (startY + pixelHeight) / level.scale)

        return TileBounds(
            key = TileKey(pageIndex = pageInfo.index, level = level.levelIndex, col = col, row = row),
            pageRect = ViewportRect(left = leftPts, top = topPts, right = rightPts, bottom = bottomPts),
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            fullWidth = level.totalPixelWidth,
            fullHeight = level.totalPixelHeight,
            startX = startX,
            startY = startY
        )
    }

    /**
     * Returns all tiles in [level] that intersect [viewportInPoints].
     */
    fun getVisibleTiles(
        pageInfo: PageInfo,
        level: PyramidLevel,
        viewportInPoints: ViewportRect
    ): List<TileBounds> {
        val minCol = max(0, ((viewportInPoints.left * level.scale) / level.tileSize).toInt())
        val maxCol = min(level.cols - 1, ((viewportInPoints.right * level.scale) / level.tileSize).toInt())
        val minRow = max(0, ((viewportInPoints.top * level.scale) / level.tileSize).toInt())
        val maxRow = min(level.rows - 1, ((viewportInPoints.bottom * level.scale) / level.tileSize).toInt())

        if (minCol > maxCol || minRow > maxRow) {
            return emptyList()
        }

        val visible = ArrayList<TileBounds>((maxCol - minCol + 1) * (maxRow - minRow + 1))
        for (r in minRow..maxRow) {
            for (c in minCol..maxCol) {
                visible.add(computeTileBounds(pageInfo, level, c, r))
            }
        }
        return visible
    }

    /**
     * Computes the prefetch ring adjacent to [visibleTiles] in the direction of scroll.
     * Per PRD §4.5: "prefetch ring in the scroll/fling direction".
     */
    fun getPrefetchRing(
        pageInfo: PageInfo,
        level: PyramidLevel,
        visibleTiles: List<TileBounds>,
        scrollDeltaX: Float = 0f,
        scrollDeltaY: Float = 0f,
        ringDepth: Int = 1
    ): List<TileBounds> {
        if (visibleTiles.isEmpty() || ringDepth <= 0) return emptyList()

        val visibleKeys = visibleTiles.mapTo(HashSet()) { it.key }
        val minCol = visibleTiles.minOf { it.key.col }
        val maxCol = visibleTiles.maxOf { it.key.col }
        val minRow = visibleTiles.minOf { it.key.row }
        val maxRow = visibleTiles.maxOf { it.key.row }

        val candidateCols = mutableListOf<IntRange>()
        val candidateRows = mutableListOf<IntRange>()

        val isDirectional = scrollDeltaX != 0f || scrollDeltaY != 0f

        if (!isDirectional) {
            // Omnidirectional border ring
            val rStart = max(0, minRow - ringDepth)
            val rEnd = min(level.rows - 1, maxRow + ringDepth)
            val cStart = max(0, minCol - ringDepth)
            val cEnd = min(level.cols - 1, maxCol + ringDepth)
            candidateRows.add(rStart..rEnd)
            candidateCols.add(cStart..cEnd)
        } else {
            // Directional prefetch
            val rStart = if (scrollDeltaY < 0f) max(0, minRow - ringDepth) else minRow
            val rEnd = if (scrollDeltaY > 0f) min(level.rows - 1, maxRow + ringDepth) else maxRow
            val cStart = if (scrollDeltaX < 0f) max(0, minCol - ringDepth) else minCol
            val cEnd = if (scrollDeltaX > 0f) min(level.cols - 1, maxCol + ringDepth) else maxCol
            candidateRows.add(rStart..rEnd)
            candidateCols.add(cStart..cEnd)
        }

        val prefetch = mutableListOf<TileBounds>()
        for (rowRange in candidateRows) {
            for (r in rowRange) {
                for (colRange in candidateCols) {
                    for (c in colRange) {
                        val key = TileKey(pageIndex = pageInfo.index, level = level.levelIndex, col = c, row = r)
                        if (key !in visibleKeys) {
                            prefetch.add(computeTileBounds(pageInfo, level, c, r))
                        }
                    }
                }
            }
        }
        return prefetch
    }

    /**
     * Computes the thumbnail bounds for a page per PRD §4.4 (~400 px wide).
     */
    fun computeThumbnailBounds(pageInfo: PageInfo, targetWidthPx: Int = 400): TileBounds {
        val scale = targetWidthPx / pageInfo.widthPoints
        val pixelWidth = targetWidthPx
        val pixelHeight = max(1, ceil(pageInfo.heightPoints * scale).toInt())
        return TileBounds(
            key = TileKey(pageIndex = pageInfo.index, level = -1, col = 0, row = 0),
            pageRect = ViewportRect(0f, 0f, pageInfo.widthPoints, pageInfo.heightPoints),
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            fullWidth = pixelWidth,
            fullHeight = pixelHeight,
            startX = 0,
            startY = 0
        )
    }

    /**
     * Computes the base layer bounds for a page at fit-width scale per PRD §4.4, capped at
     * [MAX_BASE_DIMENSION] on the long side. The base is the blurry fallback under detail tiles; at
     * full fit-width a portrait page on a 3200 px wide tablet is ~4500 px tall, which the renderer
     * rejects (4096 px limit), leaving the page blank until its tiles arrive.
     */
    fun computeBaseLevelBounds(pageInfo: PageInfo, fitWidthScale: Float): TileBounds {
        val longSide = max(pageInfo.widthPoints, pageInfo.heightPoints)
        val scale = min(fitWidthScale, MAX_BASE_DIMENSION / longSide)
        val pixelWidth = min(MAX_BASE_DIMENSION, ceil(pageInfo.widthPoints * scale).toInt())
        val pixelHeight = min(MAX_BASE_DIMENSION, ceil(pageInfo.heightPoints * scale).toInt())
        return TileBounds(
            key = TileKey(pageIndex = pageInfo.index, level = -2, col = 0, row = 0),
            pageRect = ViewportRect(0f, 0f, pageInfo.widthPoints, pageInfo.heightPoints),
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            fullWidth = pixelWidth,
            fullHeight = pixelHeight,
            startX = 0,
            startY = 0
        )
    }
}
