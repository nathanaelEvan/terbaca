package app.terbaca

import app.terbaca.feature.reader.ReaderViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPositionTest {

    @Test
    fun startsAtDocumentInitialPage() {
        val position = ReadingPosition(initialPage = 7)
        assertEquals(7, position.entryPage)
        assertEquals(7, position.currentPage)
    }

    @Test
    fun modeSwitchOpensNewModeAtCurrentPage() {
        val position = ReadingPosition(initialPage = 0)
        position.onPageChanged(29) // settled on slide 30

        position.onModeSwitch() // Slides -> Scroll
        assertEquals(29, position.entryPage)

        position.onPageChanged(41)
        position.onModeSwitch() // Scroll -> Slides
        assertEquals(41, position.entryPage)
    }

    @Test
    fun pageChangesDoNotMoveEntryPageUntilModeSwitch() {
        val position = ReadingPosition(initialPage = 3)
        position.onPageChanged(4)
        position.onPageChanged(5)
        assertEquals(3, position.entryPage)
        assertEquals(5, position.currentPage)
    }

    @Test
    fun savedViewportIsRestoredOnOpen_notAfterModeSwitch() {
        val saved = ReaderViewport(zoom = 2f, x = 40f, y = 8_000f)
        val position = ReadingPosition(initialPage = 9, initialViewport = saved)
        assertEquals(saved, position.entryViewport)
        assertEquals(saved, position.viewport)

        position.onModeSwitch() // Scroll -> Slides: the slide page is the position now
        assertNull(position.entryViewport)
        assertNull(position.viewport)

        val moved = ReaderViewport(zoom = 1f, x = 0f, y = 100f)
        position.onViewportChanged(moved)
        assertEquals(moved, position.viewport)
    }
}
