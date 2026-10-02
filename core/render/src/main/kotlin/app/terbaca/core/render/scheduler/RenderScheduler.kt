package app.terbaca.core.render.scheduler

import android.graphics.Bitmap
import app.terbaca.core.model.TileKey
import app.terbaca.core.render.pyramid.TileBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import java.util.PriorityQueue

/**
 * Priority queue render scheduler with request coalescing and cancellation per PRD §4.5.
 *
 * Rules:
 * - Priority: visible detail tiles (by distance from center) -> visible base -> prefetch ring -> adjacent base -> thumbnails.
 * - Coalescing: never more than one outstanding request for the same tile key.
 * - Cancellation: stale requests can be dropped on gesture/viewport updates.
 */
class RenderScheduler<T>(
    private val scope: CoroutineScope,
    private val maxConcurrentRenders: Int = 1,
    private val renderAction: suspend (TileBounds, CancellationToken) -> T
) {
    private val lock = Any()
    private val queuedRequests = mutableMapOf<TileKey, TileRequest<T>>()
    private val inFlightRequests = mutableMapOf<TileKey, TileRequest<T>>()
    private val priorityQueue = PriorityQueue<TileRequest<T>> { a, b -> a.priority.compareTo(b.priority) }
    private var runningRendersCount: Int = 0

    val queuedCount: Int
        get() = synchronized(lock) { queuedRequests.size }

    val inFlightCount: Int
        get() = synchronized(lock) { runningRendersCount }

    fun getQueuedKeys(): Set<TileKey> = synchronized(lock) { queuedRequests.keys.toSet() }

    fun getInFlightKeys(): Set<TileKey> = synchronized(lock) { inFlightRequests.keys.toSet() }

    /**
     * Enqueues a render request for [bounds] with [priority].
     * If an identical [TileKey] is already queued, upgrades priority if [priority] is higher
     * and returns the existing [Deferred].
     * If already in flight, returns the in-flight [Deferred].
     */
    fun enqueue(bounds: TileBounds, priority: TilePriority): Deferred<T> {
        val requestToStart = synchronized(lock) {
            inFlightRequests[bounds.key]?.let { inFlight ->
                if (!inFlight.isCancelled) {
                    return inFlight.deferred
                }
            }

            queuedRequests[bounds.key]?.let { existing ->
                if (priority < existing.priority) {
                    existing.priority = priority
                    priorityQueue.remove(existing)
                    priorityQueue.offer(existing)
                }
                return existing.deferred
            }

            val newRequest = TileRequest<T>(bounds, priority)
            queuedRequests[bounds.key] = newRequest
            priorityQueue.offer(newRequest)
            dispatchNextLocked()
            newRequest
        }

        return requestToStart.deferred
    }

    /**
     * Enqueues a batch of requests, coalescing with existing requests.
     */
    fun enqueueBatch(requests: List<Pair<TileBounds, TilePriority>>): Map<TileKey, Deferred<T>> {
        val resultMap = mutableMapOf<TileKey, Deferred<T>>()
        synchronized(lock) {
            for ((bounds, priority) in requests) {
                val key = bounds.key
                inFlightRequests[key]?.let { inFlight ->
                    if (!inFlight.isCancelled) {
                        resultMap[key] = inFlight.deferred
                        return@let
                    }
                }

                queuedRequests[key]?.let { existing ->
                    if (priority < existing.priority) {
                        existing.priority = priority
                        priorityQueue.remove(existing)
                        priorityQueue.offer(existing)
                    }
                    resultMap[key] = existing.deferred
                    return@let
                }

                if (key !in resultMap) {
                    val newRequest = TileRequest<T>(bounds, priority)
                    queuedRequests[key] = newRequest
                    priorityQueue.offer(newRequest)
                    resultMap[key] = newRequest.deferred
                }
            }
            dispatchNextLocked()
        }
        return resultMap
    }

    /**
     * Cancels any queued or in-flight request for [key].
     */
    fun cancel(key: TileKey) {
        synchronized(lock) {
            queuedRequests.remove(key)?.let { req ->
                priorityQueue.remove(req)
                req.cancel()
            }
            inFlightRequests[key]?.cancel()
        }
    }

    /**
     * Cancels any queued or in-flight requests that are not in [activeKeys].
     */
    fun cancelStaleExcept(activeKeys: Set<TileKey>) {
        synchronized(lock) {
            val iterator = queuedRequests.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in activeKeys) {
                    iterator.remove()
                    priorityQueue.remove(entry.value)
                    entry.value.cancel()
                }
            }
            for ((key, req) in inFlightRequests) {
                if (key !in activeKeys) {
                    req.cancel()
                }
            }
        }
    }

    /**
     * Cancels all pending and in-flight requests.
     */
    fun cancelAll() {
        synchronized(lock) {
            queuedRequests.values.forEach { it.cancel() }
            queuedRequests.clear()
            priorityQueue.clear()
            inFlightRequests.values.forEach { it.cancel() }
        }
    }

    private fun dispatchNextLocked() {
        while (runningRendersCount < maxConcurrentRenders && priorityQueue.isNotEmpty()) {
            val next = priorityQueue.poll() ?: break
            if (next.isCancelled) {
                queuedRequests.remove(next.key)
                continue
            }
            queuedRequests.remove(next.key)
            inFlightRequests[next.key] = next
            runningRendersCount++
            scope.launch {
                executeRequest(next)
            }
        }
    }

    private suspend fun executeRequest(request: TileRequest<T>) {
        val token = CancellationToken { request.isCancelled }
        try {
            if (request.isCancelled) {
                request.deferred.cancel()
                return
            }
            val result = renderAction(request.bounds, token)
            if (request.isCancelled) {
                (result as? Bitmap)?.let { bmp ->
                    runCatching { if (!bmp.isRecycled) bmp.recycle() }
                }
                request.deferred.cancel()
            } else {
                request.deferred.complete(result)
            }
        } catch (e: CancellationException) {
            request.deferred.cancel(e)
        } catch (t: Throwable) {
            request.deferred.completeExceptionally(t)
        } finally {
            synchronized(lock) {
                if (inFlightRequests[request.key] === request) {
                    inFlightRequests.remove(request.key)
                }
                runningRendersCount--
                dispatchNextLocked()
            }
        }
    }
}
