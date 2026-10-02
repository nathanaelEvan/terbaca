package app.terbaca.core.render.scheduler

import app.terbaca.core.model.PageInfo
import app.terbaca.core.model.TileKey
import app.terbaca.core.render.pyramid.TilePyramid
import app.terbaca.core.render.pyramid.ViewportRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class RenderSchedulerTest {

    private val page = PageInfo(0, 612f, 792f)
    private val levels = TilePyramid.buildLevels(page, 1000f, 1000f)
    private val level = levels[1]

    private fun createBounds(col: Int, row: Int, pageIndex: Int = 0): app.terbaca.core.render.pyramid.TileBounds {
        return TilePyramid.computeTileBounds(page.copy(index = pageIndex), level, col, row)
    }

    @Test
    fun testPriorityOrdering_executesInPrdOrder() = runTest {
        val executedKeys = CopyOnWriteArrayList<TileKey>()
        val executionTrigger = CompletableDeferred<Unit>()

        val scheduler = RenderScheduler<String>(
            scope = this,
            maxConcurrentRenders = 1
        ) { bounds, _ ->
            executionTrigger.await()
            executedKeys.add(bounds.key)
            "done_${bounds.key}"
        }

        // Create tiles within grid bounds
        val tileFar = createBounds(0, 0)
        val tileNear = createBounds(1, 1)
        val tileBase = createBounds(2, 2)
        val tilePrefetch = createBounds(2, 1)
        val tileAdjacent = createBounds(0, 0, pageIndex = 1)
        val tileThumb = createBounds(0, 0, pageIndex = 2)

        // Enqueue in reverse priority order
        scheduler.enqueue(tileThumb, TilePriority(TileLayerType.THUMBNAIL))
        scheduler.enqueue(tileAdjacent, TilePriority(TileLayerType.ADJACENT_BASE))
        scheduler.enqueue(tilePrefetch, TilePriority(TileLayerType.PREFETCH_DETAIL))
        scheduler.enqueue(tileBase, TilePriority(TileLayerType.VISIBLE_BASE))
        scheduler.enqueue(tileFar, TilePriority(TileLayerType.VISIBLE_DETAIL, distanceToCenter = 500f))
        scheduler.enqueue(tileNear, TilePriority(TileLayerType.VISIBLE_DETAIL, distanceToCenter = 50f))

        // First item (tileThumb) was dispatched immediately because concurrency was 0.
        // Release execution so the scheduler processes the remaining items by priority.
        executionTrigger.complete(Unit)

        // Wait for all to finish
        testScheduler.advanceUntilIdle()

        // After the initially dispatched item, the remaining queued items must be processed strictly by priority
        val remainingOrder = executedKeys.drop(1)
        assertEquals(
            listOf(
                tileNear.key,      // VISIBLE_DETAIL (closest to center)
                tileFar.key,       // VISIBLE_DETAIL (further from center)
                tileBase.key,      // VISIBLE_BASE
                tilePrefetch.key,  // PREFETCH_DETAIL
                tileAdjacent.key   // ADJACENT_BASE
            ),
            remainingOrder
        )
    }

    @Test
    fun testCoalescing_queuedRequestUpgradesPriorityAndReusesDeferred() = runTest {
        var renderCount = 0
        val blockFirst = CompletableDeferred<Unit>()

        val scheduler = RenderScheduler<String>(
            scope = this,
            maxConcurrentRenders = 1
        ) { bounds, _ ->
            renderCount++
            if (bounds.key.col == 0) {
                blockFirst.await()
            }
            "rendered_${bounds.key}"
        }

        val blocker = createBounds(0, 0)
        val target = createBounds(1, 1)

        // Blocker goes in-flight
        scheduler.enqueue(blocker, TilePriority(TileLayerType.VISIBLE_BASE))

        // Target enqueued with low priority (thumbnail)
        val deferred1 = scheduler.enqueue(target, TilePriority(TileLayerType.THUMBNAIL))

        // Re-enqueue target with high priority (visible detail)
        val deferred2 = scheduler.enqueue(target, TilePriority(TileLayerType.VISIBLE_DETAIL, distanceToCenter = 10f))

        // Must be the same deferred (coalesced)
        assertEquals(deferred1, deferred2)
        assertEquals(1, scheduler.queuedCount)

        // Release blocker
        blockFirst.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("rendered_${target.key}", deferred1.await())
        assertEquals("rendered_${target.key}", deferred2.await())
        assertEquals(2, renderCount) // 1 for blocker + 1 for target
    }

    @Test
    fun testCoalescing_inFlightRequestReusesDeferred() = runTest {
        val inFlightGate = CompletableDeferred<Unit>()
        var renderCount = 0

        val scheduler = RenderScheduler<String>(
            scope = this,
            maxConcurrentRenders = 1
        ) { _, _ ->
            renderCount++
            inFlightGate.await()
            "success"
        }

        val tile = createBounds(0, 0)
        val deferred1 = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_BASE))

        // Advance slightly so it transitions to in-flight
        testScheduler.runCurrent()
        assertEquals(1, scheduler.inFlightCount)

        // Second request while in-flight
        val deferred2 = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_DETAIL))
        assertEquals(deferred1, deferred2)

        inFlightGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("success", deferred1.await())
        assertEquals("success", deferred2.await())
        assertEquals(1, renderCount)
    }

    @Test
    fun testCancellation_removesFromQueue() = runTest {
        val blockerGate = CompletableDeferred<Unit>()
        val executedKeys = mutableListOf<TileKey>()

        val scheduler = RenderScheduler<String>(
            scope = this,
            maxConcurrentRenders = 1
        ) { bounds, _ ->
            if (bounds.key.col == 0) {
                blockerGate.await()
            }
            executedKeys.add(bounds.key)
            "done"
        }

        val blocker = createBounds(0, 0)
        val tileToCancel = createBounds(1, 1)
        val tileToKeep = createBounds(2, 2)

        scheduler.enqueue(blocker, TilePriority(TileLayerType.VISIBLE_BASE))
        val deferredCancel = scheduler.enqueue(tileToCancel, TilePriority(TileLayerType.VISIBLE_DETAIL))
        val deferredKeep = scheduler.enqueue(tileToKeep, TilePriority(TileLayerType.VISIBLE_DETAIL))

        // Cancel tileToCancel
        scheduler.cancel(tileToCancel.key)
        assertTrue(deferredCancel.isCancelled)
        assertFalse(deferredKeep.isCancelled)

        blockerGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(blocker.key, tileToKeep.key), executedKeys)
    }

    @Test
    fun testCancelStaleExcept_dropsNonActiveTiles() = runTest {
        val blockerGate = CompletableDeferred<Unit>()
        val executedKeys = mutableListOf<TileKey>()

        val scheduler = RenderScheduler<String>(
            scope = this,
            maxConcurrentRenders = 1
        ) { bounds, _ ->
            if (bounds.key.col == 0) blockerGate.await()
            executedKeys.add(bounds.key)
            "done"
        }

        val blocker = createBounds(0, 0, pageIndex = 0)
        val page0Tile = createBounds(1, 1, pageIndex = 0)
        val page1Tile = createBounds(1, 1, pageIndex = 1)
        val page2Tile = createBounds(1, 1, pageIndex = 2)

        scheduler.enqueue(blocker, TilePriority(TileLayerType.VISIBLE_BASE))
        val defPage0 = scheduler.enqueue(page0Tile, TilePriority(TileLayerType.VISIBLE_DETAIL))
        val defPage1 = scheduler.enqueue(page1Tile, TilePriority(TileLayerType.VISIBLE_DETAIL))
        val defPage2 = scheduler.enqueue(page2Tile, TilePriority(TileLayerType.VISIBLE_DETAIL))

        // Viewport moves to page 1: drop all other pages
        scheduler.cancelStaleExcept(setOf(blocker.key, page1Tile.key))

        assertTrue(defPage0.isCancelled)
        assertTrue(defPage2.isCancelled)
        assertFalse(defPage1.isCancelled)

        blockerGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(blocker.key, page1Tile.key), executedKeys)
    }

    @Test
    fun testInFlightCancellation_signalsCancellationToken() = runTest {
        val startedGate = CompletableDeferred<Unit>()
        var cancellationDetected = false

        val scheduler = RenderScheduler<String>(
            scope = this,
            maxConcurrentRenders = 1
        ) { _, token ->
            startedGate.complete(Unit)
            while (!token.isCancelled()) {
                delay(10)
            }
            cancellationDetected = true
            throw CancellationException("Aborted")
        }

        val tile = createBounds(0, 0)
        val deferred = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_DETAIL))

        testScheduler.runCurrent()
        startedGate.await()

        // Cancel while running
        scheduler.cancel(tile.key)
        testScheduler.advanceUntilIdle()

        assertTrue("Worker must detect cancellation token", cancellationDetected)
        assertTrue(deferred.isCancelled)
    }
}
