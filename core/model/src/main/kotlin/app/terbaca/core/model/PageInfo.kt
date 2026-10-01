package app.terbaca.core.model

data class PageInfo(
    val index: Int,
    val widthPoints: Float,
    val heightPoints: Float
) {
    val aspectRatio: Float
        get() = if (heightPoints > 0f) widthPoints / heightPoints else 1f

    val isLandscape: Boolean
        get() = widthPoints > heightPoints
}
