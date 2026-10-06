package app.terbaca

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.terbaca.feature.reader.ReaderViewport

/**
 * The reading position of the open document, carried across a mode switch (F-MODE-5) so slide 30
 * opens page 30 in scroll mode and back, and saved per document (F-LIB-2).
 *
 * [currentPage] and [viewport] are plain fields: they change on every frame of a fling, and
 * routing them through snapshot state would recompose the whole reader each time (PRD §4.6).
 * Only [entryPage], the page the newly shown mode opens at, is observable, and it changes once
 * per mode switch.
 */
internal class ReadingPosition(initialPage: Int, initialViewport: ReaderViewport? = null) {
    var currentPage: Int = initialPage
        private set

    var entryPage: Int by mutableIntStateOf(initialPage)
        private set

    /** The saved scroll-mode viewport to restore on open; a mode switch opens at [entryPage]. */
    var entryViewport: ReaderViewport? = initialViewport
        private set

    /** The scroll-mode viewport, or null after a switch to slides (it no longer matches the page). */
    var viewport: ReaderViewport? = initialViewport
        private set

    fun onPageChanged(page: Int) {
        currentPage = page
    }

    fun onViewportChanged(viewport: ReaderViewport) {
        this.viewport = viewport
    }

    fun onModeSwitch() {
        entryPage = currentPage
        entryViewport = null
        viewport = null
    }
}
