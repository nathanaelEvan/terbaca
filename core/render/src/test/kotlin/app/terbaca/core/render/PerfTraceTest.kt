package app.terbaca.core.render

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerfTraceTest {

    private class FakeTracer(override var isEnabled: Boolean) : PerfTrace.Tracer {
        val events = mutableListOf<String>()
        override fun beginAsync(name: String, cookie: Int) { events += "begin $name" }
        override fun endAsync(name: String, cookie: Int) { events += "end $name" }
        override fun mark(name: String) { events += "mark $name" }
    }

    private val tracer = FakeTracer(isEnabled = true)

    @Before
    fun setUp() {
        PerfTrace.tracer = tracer
    }

    @After
    fun tearDown() {
        PerfTrace.endSharpen()
        PerfTrace.abandonOpen()
        PerfTrace.tracer = PerfTrace.SystemTracer
    }

    @Test
    fun tracingOff_nothingStartsAndNothingIsPending() {
        tracer.isEnabled = false
        PerfTrace.beginOpen()
        assertFalse(PerfTrace.beginSharpen())
        assertFalse(PerfTrace.isOpenPending)
        assertFalse(PerfTrace.isSharpenPending)
        PerfTrace.endOpen()
        PerfTrace.endSharpen()
        assertTrue(tracer.events.isEmpty())
    }

    @Test
    fun sharpen_beginsOnceAndEndsOnce() {
        assertTrue(PerfTrace.beginSharpen())
        assertTrue(PerfTrace.beginSharpen())
        PerfTrace.endSharpen()
        PerfTrace.endSharpen()
        assertEquals(listOf("begin ${PerfTrace.SHARPEN}", "end ${PerfTrace.SHARPEN}"), tracer.events)
    }

    @Test
    fun cancelSharpen_endsAndMarks_onlyWhenPending() {
        PerfTrace.cancelSharpen()
        assertTrue(tracer.events.isEmpty())
        PerfTrace.beginSharpen()
        PerfTrace.cancelSharpen()
        assertFalse(PerfTrace.isSharpenPending)
        assertEquals(
            listOf("begin ${PerfTrace.SHARPEN}", "end ${PerfTrace.SHARPEN}", "mark ${PerfTrace.SHARPEN_CANCELLED}"),
            tracer.events
        )
    }

    @Test
    fun abandonedOpen_isLeftUnfinished_andALaterEndDoesNothing() {
        PerfTrace.beginOpen()
        PerfTrace.abandonOpen()
        assertFalse(PerfTrace.isOpenPending)
        PerfTrace.endOpen()
        assertEquals(listOf("begin ${PerfTrace.OPEN_TO_SHARP}"), tracer.events)
    }

    @Test
    fun open_endsOnce() {
        PerfTrace.beginOpen()
        assertTrue(PerfTrace.isOpenPending)
        PerfTrace.endOpen()
        PerfTrace.endOpen()
        assertEquals(listOf("begin ${PerfTrace.OPEN_TO_SHARP}", "end ${PerfTrace.OPEN_TO_SHARP}"), tracer.events)
    }
}
