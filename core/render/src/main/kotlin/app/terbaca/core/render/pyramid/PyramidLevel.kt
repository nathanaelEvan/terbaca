package app.terbaca.core.render.pyramid

/**
 * Metadata for a single discrete zoom level in the tile pyramid.
 *
 * Per PRD §4.4:
 * Discrete levels are powers of sqrt(2) from fit-page scale up to 8x fit-width scale.
 * Coordinates are in device pixels per PDF point (scale).
 */
data class PyramidLevel(
    val levelIndex: Int,
    val scale: Float,
    val relativeZoom: Float,
    val cols: Int,
    val rows: Int,
    val totalPixelWidth: Int,
    val totalPixelHeight: Int,
    val tileSize: Int
) {
    val totalTiles: Int get() = cols * rows
}
