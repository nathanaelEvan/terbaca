package app.terbaca.feature.slides

import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Slide navigation actions triggered by keyboard or clicker (PRD F-NAV-7, F-DECK-2).
 */
enum class SlideNavAction {
    NEXT,
    PREVIOUS,
    FIRST,
    LAST,
    SEARCH,
    NONE
}

/**
 * Resolves a [KeyEvent] to a [SlideNavAction].
 */
fun resolveSlideKeyAction(event: KeyEvent): SlideNavAction {
    if (event.type != KeyEventType.KeyDown) return SlideNavAction.NONE
    val isCtrl = event.isCtrlPressed
    val isShift = event.isShiftPressed

    return when {
        isCtrl && event.key == Key.F -> SlideNavAction.SEARCH
        // Next slide: Right, Down, PageDown, Space
        event.key == Key.DirectionRight ||
            event.key == Key.DirectionDown ||
            event.key == Key.PageDown ||
            (event.key == Key.Spacebar && !isShift) -> SlideNavAction.NEXT
        // Previous slide: Left, Up, PageUp, Shift+Space
        event.key == Key.DirectionLeft ||
            event.key == Key.DirectionUp ||
            event.key == Key.PageUp ||
            (event.key == Key.Spacebar && isShift) -> SlideNavAction.PREVIOUS
        // First slide: Home
        event.key == Key.MoveHome -> SlideNavAction.FIRST
        // Last slide: End
        event.key == Key.MoveEnd -> SlideNavAction.LAST
        else -> SlideNavAction.NONE
    }
}

/**
 * Handles keyboard navigation for slide mode per PRD F-NAV-7 & F-DECK-2.
 *
 * @return true if the event was consumed.
 */
fun handleSlideKeyEvent(
    event: KeyEvent,
    pagerState: PagerState,
    pageCount: Int,
    coroutineScope: CoroutineScope,
    onSearch: (() -> Unit)? = null
): Boolean {
    val action = resolveSlideKeyAction(event)
    return when (action) {
        SlideNavAction.SEARCH -> {
            onSearch?.invoke()
            true
        }
        SlideNavAction.NEXT -> {
            if (pagerState.currentPage < pageCount - 1) {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                }
            }
            true
        }
        SlideNavAction.PREVIOUS -> {
            if (pagerState.currentPage > 0) {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                }
            }
            true
        }
        SlideNavAction.FIRST -> {
            coroutineScope.launch {
                pagerState.animateScrollToPage(0)
            }
            true
        }
        SlideNavAction.LAST -> {
            coroutineScope.launch {
                pagerState.animateScrollToPage(pageCount - 1)
            }
            true
        }
        SlideNavAction.NONE -> false
    }
}
