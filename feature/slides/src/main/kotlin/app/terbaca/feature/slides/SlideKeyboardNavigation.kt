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
 * Page a navigation [action] should scroll to, or null if it doesn't move the pager.
 *
 * Steps from [targetPage] (the page an in-flight animation is heading to), not the page currently
 * on screen, so a second press during the flip animation still advances one more slide.
 */
fun slideNavTargetPage(action: SlideNavAction, targetPage: Int, pageCount: Int): Int? {
    if (pageCount <= 0) return null
    val lastPage = pageCount - 1
    val destination = when (action) {
        SlideNavAction.NEXT -> targetPage + 1
        SlideNavAction.PREVIOUS -> targetPage - 1
        SlideNavAction.FIRST -> 0
        SlideNavAction.LAST -> lastPage
        SlideNavAction.SEARCH, SlideNavAction.NONE -> return null
    }.coerceIn(0, lastPage)
    return destination.takeIf { it != targetPage }
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
    when (action) {
        SlideNavAction.NONE -> return false
        SlideNavAction.SEARCH -> onSearch?.invoke()
        else -> slideNavTargetPage(action, pagerState.targetPage, pageCount)?.let { page ->
            coroutineScope.launch { pagerState.animateScrollToPage(page) }
        }
    }
    return true
}
