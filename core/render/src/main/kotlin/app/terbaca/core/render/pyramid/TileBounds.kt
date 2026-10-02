package app.terbaca.core.render.pyramid

import app.terbaca.core.model.TileKey

/**
 * Coordinate geometry and pixel resolution for a specific tile in the pyramid.
 */
data class TileBounds(
    val key: TileKey,
    // Page coordinate rectangle in PDF points
    val pageRect: ViewportRect,
    // Exact pixel dimensions for the rendered tile buffer (edge tiles may be smaller than tileSize)
    val pixelWidth: Int,
    val pixelHeight: Int,
    val fullWidth: Int = 0,
    val fullHeight: Int = 0,
    val startX: Int = 0,
    val startY: Int = 0
)
