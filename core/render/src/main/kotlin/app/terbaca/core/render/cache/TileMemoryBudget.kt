package app.terbaca.core.render.cache

import android.app.ActivityManager
import android.content.Context

/**
 * Memory budget configuration for tile caches per PRD §5.
 *
 * Tier budgets:
 * - Hot tier: visible tiles + 1-tile prefetch ring in movement direction (~80 MB). Advisory:
 *   visible tiles are never evicted, so this is the expected size, not a hard cap.
 * - Warm tier: recently visible tiles and base layers, LRU evicted by bytes (~80 MB)
 * - Thumbnail tier: all pages in-memory thumbnail cache (~20 MB)
 *
 * Automatically scales down proportionally if [memoryClassMb] is less than 256 MB.
 */
data class TileMemoryBudget(
    val hotBytes: Long = DEFAULT_HOT_BYTES,
    val warmBytes: Long = DEFAULT_WARM_BYTES,
    val thumbnailBytes: Long = DEFAULT_THUMBNAIL_BYTES
) {
    companion object {
        const val DEFAULT_HOT_BYTES = 80L * 1024 * 1024 // 80 MB
        const val DEFAULT_WARM_BYTES = 80L * 1024 * 1024 // 80 MB
        const val DEFAULT_THUMBNAIL_BYTES = 20L * 1024 * 1024 // 20 MB
        private const val BASELINE_MEMORY_CLASS_MB = 256

        /**
         * Computes memory budget based on device memory class in MB.
         */
        fun fromMemoryClass(memoryClassMb: Int): TileMemoryBudget {
            if (memoryClassMb >= BASELINE_MEMORY_CLASS_MB) {
                return TileMemoryBudget()
            }
            val ratio = (memoryClassMb.toFloat() / BASELINE_MEMORY_CLASS_MB.toFloat()).coerceIn(0.25f, 1f)
            return TileMemoryBudget(
                hotBytes = (DEFAULT_HOT_BYTES * ratio).toLong(),
                warmBytes = (DEFAULT_WARM_BYTES * ratio).toLong(),
                thumbnailBytes = (DEFAULT_THUMBNAIL_BYTES * ratio).toLong()
            )
        }

        /**
         * Resolves memory budget from the application [Context].
         */
        fun fromContext(context: Context): TileMemoryBudget {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memClass = am?.memoryClass ?: BASELINE_MEMORY_CLASS_MB
            return fromMemoryClass(memClass)
        }
    }
}
