package app.terbaca.core.render.scheduler

import app.terbaca.core.model.TileKey
import app.terbaca.core.render.pyramid.TileBounds
import kotlinx.coroutines.CompletableDeferred

/**
 * Encapsulates a queued or in-flight tile render request with its associated priority and result deferred.
 */
class TileRequest<T>(
    val bounds: TileBounds,
    priority: TilePriority,
    val deferred: CompletableDeferred<T> = CompletableDeferred()
) {
    val key: TileKey get() = bounds.key

    @Volatile
    var priority: TilePriority = priority
        internal set

    @Volatile
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        if (!isCancelled) {
            isCancelled = true
            deferred.cancel()
        }
    }
}
