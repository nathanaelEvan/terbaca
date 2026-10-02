package app.terbaca.core.render.pyramid

import kotlin.math.max

/**
 * Axis-aligned bounding box representation in PDF point space.
 */
data class ViewportRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left <= right) { "left ($left) must be <= right ($right)" }
        require(top <= bottom) { "top ($top) must be <= bottom ($bottom)" }
    }

    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersects(other: ViewportRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom
}
