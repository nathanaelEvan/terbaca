package app.terbaca.core.render.scheduler

/**
 * Priority key for sorting tile requests in the scheduler queue per PRD §4.5.
 * Lower natural ordering = higher execution priority.
 */
data class TilePriority(
    val layerType: TileLayerType,
    val distanceToCenter: Float = 0f,
    val sequenceNumber: Long = 0L
) : Comparable<TilePriority> {

    override fun compareTo(other: TilePriority): Int {
        if (layerType != other.layerType) {
            return layerType.rank.compareTo(other.layerType.rank)
        }
        val distDiff = distanceToCenter.compareTo(other.distanceToCenter)
        if (distDiff != 0) return distDiff
        return sequenceNumber.compareTo(other.sequenceNumber)
    }
}
