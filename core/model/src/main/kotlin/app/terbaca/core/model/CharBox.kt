package app.terbaca.core.model

import kotlin.math.max

/**
 * Axis-aligned bounding box of a character in page coordinates (top-left origin).
 * [top] is distance from top of page, [bottom] is distance from top of page (top <= bottom).
 */
data class CharBox(
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
}
