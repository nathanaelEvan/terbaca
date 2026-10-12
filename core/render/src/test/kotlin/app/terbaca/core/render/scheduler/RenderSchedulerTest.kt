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
import org.junit.Assert.assertNotSame
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
                tileBase.key,      // VISIBLE_BASE
                tileNear.key,      // VISIBLE_DETAIL (closest to center)
                tileFar.key,       // VISIBLE_DETAIL (further from center)
                tileAdjacent.key,  // ADJACENT_BASE
                tilePrefetch.key   // PREFETCH_DETAIL
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

    @Test
    fun testReEnqueueCancelledInFlight_createsNewDeferred() = runTest {
        val startedGate = CompletableDeferred<Unit>()
        val completeGate = CompletableDeferred<Unit>()
        var renderCount = 0

        val scheduler = RenderScheduler<String>(
            scope = this,
            maxConcurrentRenders = 1
        ) { _, token ->
            renderCount++
            if (renderCount == 1) {
                startedGate.complete(Unit)
                while (!token.isCancelled()) {
                    delay(10)
                }
                throw CancellationException("Cancelled")
            } else {
                completeGate.await()
                "success_re-enqueued"
            }
        }

        val tile = createBounds(0, 0)
        val deferred1 = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_BASE))

        testScheduler.runCurrent()
        startedGate.await()

        scheduler.cancel(tile.key)
        assertTrue(deferred1.isCancelled)

        val deferred2 = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_DETAIL))
        assertFalse(deferred2.isCancelled)
        assertNotSame(deferred1, deferred2)

        testScheduler.runCurrent()
        completeGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("success_re-enqueued", deferred2.await())
        assertEquals(2, renderCount)
    }

    private val unsafe: sun.misc.Unsafe by lazy {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        field.get(null) as sun.misc.Unsafe
    }

    private fun createFakeBitmap(): android.graphics.Bitmap {
        return unsafe.allocateInstance(android.graphics.Bitmap::class.java) as android.graphics.Bitmap
    }

    @Test
    fun testCancellation_triggersCancelTileOnRenderer() = runTest {
        val startedGate = CompletableDeferred<Unit>()
        val renderer = app.terbaca.core.render.InProcessPageRenderer(
            renderDelayMs = 1000L,
            onRenderStarted = { startedGate.complete(Unit) },
            bitmapSupplier = { _, _ -> createFakeBitmap() }
        )
        val docHandle = renderer.createTestDocument()
        val expectedRenderId = 777L

        val scheduler = RenderScheduler<android.graphics.Bitmap>(
            scope = this,
            maxConcurrentRenders = 1
        ) { bounds, token ->
            renderer.renderTile(
                renderId = expectedRenderId,
                docHandle = docHandle,
                pageIndex = bounds.key.pageIndex,
                fullWidth = bounds.fullWidth,
                fullHeight = bounds.fullHeight,
                startX = bounds.startX,
                startY = bounds.startY,
                tileWidth = bounds.pixelWidth,
                tileHeight = bounds.pixelHeight,
                cancellationToken = token
            )
        }

        val tile = createBounds(0, 0)
        val deferred = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_DETAIL))

        testScheduler.runCurrent()
        startedGate.await()

        // Cancel while render is in flight
        scheduler.cancel(tile.key)
        testScheduler.advanceUntilIdle()

        assertTrue(
            "Renderer must record cancelTile for expectedRenderId",
            renderer.cancelledRenderIds.contains(expectedRenderId)
        )
        assertTrue(deferred.isCancelled)
    }

    @Test
    fun testReEnqueueAfterCancellation_preservesInFlightCoalescingWhenOldFinishes() = runTest {
        var renderCount = 0
        val render1Started = CompletableDeferred<Unit>()
        val render1Finish = CompletableDeferred<Unit>()
        val render2Started = CompletableDeferred<Unit>()
        val render2Finish = CompletableDeferred<Unit>()

        val scheduler = RenderScheduler<String>(
            scope = this,
            maxConcurrentRenders = 2
        ) { bounds, _ ->
            val count = ++renderCount
            if (count == 1) {
                render1Started.complete(Unit)
                render1Finish.await()
                "result_1"
            } else if (count == 2) {
                render2Started.complete(Unit)
                render2Finish.await()
                "result_2"
            } else {
                "result_$count"
            }
        }

        val tile = createBounds(0, 0)

        // 1. Enqueue K -> starts render 1
        val def1 = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_BASE))
        testScheduler.runCurrent()
        render1Started.await()
        assertEquals(1, scheduler.inFlightCount)

        // 2. Cancel K -> render 1 marked cancelled, but still in-flight
        scheduler.cancel(tile.key)
        assertTrue(def1.isCancelled)

        // 3. Re-enqueue K -> starts render 2 because maxConcurrentRenders = 2
        val def2 = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_DETAIL))
        testScheduler.runCurrent()
        render2Started.await()
        assertEquals(2, scheduler.inFlightCount)
        assertEquals(2, renderCount)

        // 4. Finish the old render 1
        render1Finish.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, scheduler.inFlightCount)

        // 5. Enqueue K again -> must coalesce with render 2 (no second render)
        val def3 = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_DETAIL))
        assertEquals("Enqueuing K while render 2 is running must return def2", def2, def3)
        assertEquals("Render count must still be 2", 2, renderCount)

        // 6. Finish render 2
        render2Finish.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("result_2", def2.await())
        assertEquals("result_2", def3.await())
        assertEquals(2, renderCount)
        assertEquals(0, scheduler.inFlightCount)
    }

    @Test
    fun testReEnqueueAfterCancellation_respectsConcurrencyCap() = runTest {
        var renderCount = 0
        val render1Started = CompletableDeferred<Unit>()
        val render1Finish = CompletableDeferred<Unit>()
        val render2Started = CompletableDeferred<Unit>()
        val render2Finish = CompletableDeferred<Unit>()

        val scheduler = RenderScheduler<String>(
            scope = this,
            maxConcurrentRenders = 1
        ) { bounds, _ ->
            val count = ++renderCount
            if (count == 1) {
                render1Started.complete(Unit)
                render1Finish.await()
                "result_1"
            } else {
                render2Started.complete(Unit)
                render2Finish.await()
                "result_2"
            }
        }

        val tile = createBounds(0, 0)

        // 1. Enqueue K -> starts render 1
        val def1 = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_BASE))
        testScheduler.runCurrent()
        render1Started.await()
        assertEquals(1, scheduler.inFlightCount)

        // 2. Cancel K
        scheduler.cancel(tile.key)
        assertTrue(def1.isCancelled)

        // 3. Re-enqueue K -> must be queued, NOT in flight yet because cap is 1
        val def2 = scheduler.enqueue(tile, TilePriority(TileLayerType.VISIBLE_DETAIL))
        testScheduler.runCurrent()
        assertEquals("Only render 1 can be running", 1, scheduler.inFlightCount)
        assertEquals("Render 2 has not started yet", 1, renderCount)
        assertEquals(1, scheduler.queuedCount)

        // 4. Finish render 1 -> now render 2 starts
        render1Finish.complete(Unit)
        testScheduler.runCurrent()
        render2Started.await()
        assertEquals(1, scheduler.inFlightCount)
        assertEquals(2, renderCount)

        // 5. Complete render 2
        render2Finish.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("result_2", def2.await())
        assertEquals(0, scheduler.inFlightCount)
    }

    @Test
    fun enqueuingTilesFarToNear_dispatchesNearToFar() = runTest {
        val dispatchedKeys = CopyOnWriteArrayList<TileKey>()
        val gate = CompletableDeferred<Unit>()

        val scheduler = RenderScheduler<String>(
            scope = this,
            maxConcurrentRenders = 1
        ) { bounds, _ ->
            if (bounds.key.pageIndex == 99) {
                gate.await()
            }
            dispatchedKeys.add(bounds.key)
            "done_${bounds.key}"
        }

        val blocker = createBounds(0, 0, pageIndex = 99)
        scheduler.enqueue(blocker, TilePriority(TileLayerType.THUMBNAIL))

        val tileNear = createBounds(0, 0, pageIndex = 0)
        val tileMid = createBounds(1, 0, pageIndex = 0)
        val tileFar = createBounds(0, 1, pageIndex = 0)

        // Enqueue strictly in far-to-near order
        scheduler.enqueue(tileFar, TilePriority(TileLayerType.VISIBLE_DETAIL, distanceToCenter = 300f))
        scheduler.enqueue(tileMid, TilePriority(TileLayerType.VISIBLE_DETAIL, distanceToCenter = 200f))
        scheduler.enqueue(tileNear, TilePriority(TileLayerType.VISIBLE_DETAIL, distanceToCenter = 100f))

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        val detailDispatches = dispatchedKeys.filter { it.pageIndex == 0 }
        assertEquals(
            listOf(tileNear.key, tileMid.key, tileFar.key),
            detailDispatches
        )
    }
}
