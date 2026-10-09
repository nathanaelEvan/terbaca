package app.terbaca.core.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentStateWriterTest {
    private val written = mutableListOf<DocumentState>()

    private fun TestScope.writer() = DocumentStateWriter(
        scope = backgroundScope,
        debounceMillis = 500,
        now = { testScheduler.currentTime }
    ) { written += it }

    private fun state(page: Int, hash: String = "a") = DocumentState(hash, page)

    @Test
    fun flingOfPageChanges_writesOnlyTheLastOnceQuiet() = runTest {
        val writer = writer()
        for (page in 0..30) {
            writer.submit(state(page))
            advanceTimeBy(16) // one frame
        }
        assertEquals(emptyList<DocumentState>(), written)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(state(30)), written)
    }

    @Test
    fun eachChangeRestartsTheQuietPeriod() = runTest {
        val writer = writer()
        writer.submit(state(1))
        advanceTimeBy(400)
        writer.submit(state(2))
        advanceTimeBy(400)
        runCurrent()
        assertEquals(emptyList<DocumentState>(), written) // 800 ms after the first, 400 after the last

        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf(state(2)), written)
    }

    @Test
    fun flush_writesPendingStateImmediately_once() = runTest {
        val writer = writer()
        writer.submit(state(4))
        writer.flush()
        runCurrent()
        assertEquals(listOf(state(4)), written)

        advanceTimeBy(1_000)
        writer.flush()
        runCurrent()
        assertEquals(listOf(state(4)), written)
    }

    @Test
    fun switchingDocument_writesThePreviousDocumentsLastState() = runTest {
        val writer = writer()
        writer.submit(state(9, hash = "a"))
        writer.submit(state(0, hash = "b"))
        runCurrent()
        assertEquals(listOf(state(9, hash = "a")), written)

        advanceTimeBy(501)
        runCurrent()
        assertEquals(listOf(state(9, hash = "a"), state(0, hash = "b")), written)
    }

    @Test
    fun failingWrite_doesNotStopLaterWrites() = runTest {
        var fail = true
        val writer = DocumentStateWriter(backgroundScope, 500, { testScheduler.currentTime }) {
            if (fail) {
                fail = false
                error("disk full")
            }
            written += it
        }
        writer.submit(state(1))
        writer.flush()
        runCurrent()
        writer.submit(state(2))
        writer.flush()
        runCurrent()
        assertEquals(listOf(state(2)), written)
    }
}
