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
    SCROLL_LEFT,
    SCROLL_RIGHT,
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
    return resolveReaderKeyAction(event.key, event.isCtrlPressed, event.isShiftPressed)
}

/** Maps a pressed [key] with its modifiers to a [ReaderNavAction] (pure, so JVM tests can run it). */
fun resolveReaderKeyAction(key: Key, isCtrl: Boolean, isShift: Boolean): ReaderNavAction {
    return when {
        // Zoom shortcuts: Ctrl + '=', Ctrl + '+', NumPad Add
        isCtrl && (key == Key.Plus || key == Key.Equals || key == Key.NumPadAdd) ->
            ReaderNavAction.ZOOM_IN
        // Zoom shortcuts: Ctrl + '-', NumPad Subtract
        isCtrl && (key == Key.Minus || key == Key.NumPadSubtract) ->
            ReaderNavAction.ZOOM_OUT
        // Reset zoom: Ctrl + '0', NumPad 0
        isCtrl && (key == Key.Zero || key == Key.NumPad0) ->
            ReaderNavAction.ZOOM_RESET
        // Search: Ctrl + F
        isCtrl && key == Key.F ->
            ReaderNavAction.SEARCH
        // Line-by-line scrolling: Up / Down
        key == Key.DirectionUp ->
            ReaderNavAction.SCROLL_UP
        key == Key.DirectionDown ->
            ReaderNavAction.SCROLL_DOWN
        // Horizontal panning when zoomed in: Left / Right
        key == Key.DirectionLeft ->
            ReaderNavAction.SCROLL_LEFT
        key == Key.DirectionRight ->
            ReaderNavAction.SCROLL_RIGHT
        // Page scrolling: PageUp / PageDown, Space / Shift+Space
        key == Key.PageUp || (key == Key.Spacebar && isShift) ->
            ReaderNavAction.PAGE_UP
        key == Key.PageDown || (key == Key.Spacebar && !isShift) ->
            ReaderNavAction.PAGE_DOWN
        // Document boundaries: Home / End
        key == Key.MoveHome ->
            ReaderNavAction.HOME
        key == Key.MoveEnd ->
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
): Boolean = applyReaderNavAction(resolveReaderKeyAction(event), state, scrollStepPx, onSearch)

/**
 * Applies [action] to [state].
 *
 * @return true if the action was handled.
 */
fun applyReaderNavAction(
    action: ReaderNavAction,
    state: DocumentTransformState,
    scrollStepPx: Float = DEFAULT_KEYBOARD_SCROLL_STEP_PX,
    onSearch: (() -> Unit)? = null
): Boolean {
    return when (action) {
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
        ReaderNavAction.SCROLL_LEFT -> {
            state.scrollHorizontallyBy(-scrollStepPx)
            true
        }
        ReaderNavAction.SCROLL_RIGHT -> {
            state.scrollHorizontallyBy(scrollStepPx)
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
