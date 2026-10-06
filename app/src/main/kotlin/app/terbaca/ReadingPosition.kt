package app.terbaca

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * The reading position of the open document, carried across a mode switch (F-MODE-5) so slide 30
 * opens page 30 in scroll mode and back.
 *
 * [currentPage] is a plain field: page callbacks fire on every page change during a fling, and
 * routing them through snapshot state would recompose the whole reader each time (PRD §4.6).
 * Only [entryPage], the page the newly shown mode opens at, is observable, and it changes once
 * per mode switch.
 */
internal class ReadingPosition(initialPage: Int) {
    var currentPage: Int = initialPage
        private set

    var entryPage: Int by mutableIntStateOf(initialPage)
        private set

    fun onPageChanged(page: Int) {
        currentPage = page
    }

    fun onModeSwitch() {
        entryPage = currentPage
    }
}
