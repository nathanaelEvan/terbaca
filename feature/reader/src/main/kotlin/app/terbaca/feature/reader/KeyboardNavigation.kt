package app.terbaca.feature.reader

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

const val DEFAULT_KEYBOARD_SCROLL_STEP_PX = 96f

/**
 * Keyboard actions for continuous vertical reader (PRD F-NAV-7).
 */
enum class ReaderNavAction {
    ZOOM_IN,
    ZOOM_OUT,
    ZOOM_RESET,
    SEARCH,
    SCROLL_UP,
    SCROLL_DOWN,
    PAGE_UP,
    PAGE_DOWN,
    HOME,
    END,
    NONE
}

/**
 * Resolves a [KeyEvent] to a [ReaderNavAction].
 */
fun resolveReaderKeyAction(event: KeyEvent): ReaderNavAction {
    if (event.type != KeyEventType.KeyDown) return ReaderNavAction.NONE
    val isCtrl = event.isCtrlPressed
    val isShift = event.isShiftPressed

    return when {
        // Zoom shortcuts: Ctrl + '=', Ctrl + '+', NumPad Add
        isCtrl && (event.key == Key.Plus || event.key == Key.Equals || event.key == Key.NumPadAdd) ->
            ReaderNavAction.ZOOM_IN
        // Zoom shortcuts: Ctrl + '-', NumPad Subtract
        isCtrl && (event.key == Key.Minus || event.key == Key.NumPadSubtract) ->
            ReaderNavAction.ZOOM_OUT
        // Reset zoom: Ctrl + '0', NumPad 0
        isCtrl && (event.key == Key.Zero || event.key == Key.NumPad0) ->
            ReaderNavAction.ZOOM_RESET
        // Search: Ctrl + F
        isCtrl && event.key == Key.F ->
            ReaderNavAction.SEARCH
        // Line-by-line scrolling: Up / Down
        event.key == Key.DirectionUp ->
            ReaderNavAction.SCROLL_UP
        event.key == Key.DirectionDown ->
            ReaderNavAction.SCROLL_DOWN
        // Page scrolling: PageUp / PageDown, Space / Shift+Space
        event.key == Key.PageUp || (event.key == Key.Spacebar && isShift) ->
            ReaderNavAction.PAGE_UP
        event.key == Key.PageDown || (event.key == Key.Spacebar && !isShift) ->
            ReaderNavAction.PAGE_DOWN
        // Document boundaries: Home / End
        event.key == Key.MoveHome ->
            ReaderNavAction.HOME
        event.key == Key.MoveEnd ->
            ReaderNavAction.END
        else ->
            ReaderNavAction.NONE
    }
}

/**
 * Handles keyboard navigation for continuous vertical reading mode per PRD F-NAV-7.
 *
 * @return true if the event was consumed.
 */
fun handleReaderKeyEvent(
    event: KeyEvent,
    state: DocumentTransformState,
    scrollStepPx: Float = DEFAULT_KEYBOARD_SCROLL_STEP_PX,
    onSearch: (() -> Unit)? = null
): Boolean {
    return when (resolveReaderKeyAction(event)) {
        ReaderNavAction.ZOOM_IN -> {
            state.zoomIn()
            true
        }
        ReaderNavAction.ZOOM_OUT -> {
            state.zoomOut()
            true
        }
        ReaderNavAction.ZOOM_RESET -> {
            state.resetZoom()
            true
        }
        ReaderNavAction.SEARCH -> {
            onSearch?.invoke()
            true
        }
        ReaderNavAction.SCROLL_UP -> {
            state.scrollBy(-scrollStepPx)
            true
        }
        ReaderNavAction.SCROLL_DOWN -> {
            state.scrollBy(scrollStepPx)
            true
        }
        ReaderNavAction.PAGE_UP -> {
            state.scrollPage(down = false)
            true
        }
        ReaderNavAction.PAGE_DOWN -> {
            state.scrollPage(down = true)
            true
        }
        ReaderNavAction.HOME -> {
            state.scrollToTop()
            true
        }
        ReaderNavAction.END -> {
            state.scrollToBottom()
            true
        }
        ReaderNavAction.NONE -> false
    }
}
