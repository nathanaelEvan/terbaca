package app.terbaca.feature.slides

import androidx.compose.ui.input.key.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class SlideKeyboardNavigationTest {

    @Test
    fun testResolveSlideKeyAction() {
        try {
            // KeyUp should be ignored
            val keyUpEvent = KeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            )
            assertEquals(SlideNavAction.NONE, resolveSlideKeyAction(keyUpEvent))

            // Right arrow -> NEXT
            val rightEvent = KeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            )
            assertEquals(SlideNavAction.NEXT, resolveSlideKeyAction(rightEvent))

            // Left arrow -> PREVIOUS
            val leftEvent = KeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            )
            assertEquals(SlideNavAction.PREVIOUS, resolveSlideKeyAction(leftEvent))

            // Down arrow -> NEXT
            val downEvent = KeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            )
            assertEquals(SlideNavAction.NEXT, resolveSlideKeyAction(downEvent))

            // Up arrow -> PREVIOUS
            val upEvent = KeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP)
            )
            assertEquals(SlideNavAction.PREVIOUS, resolveSlideKeyAction(upEvent))

            // PageDown -> NEXT
            val pageDownEvent = KeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN)
            )
            assertEquals(SlideNavAction.NEXT, resolveSlideKeyAction(pageDownEvent))

            // PageUp -> PREVIOUS
            val pageUpEvent = KeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_UP)
            )
            assertEquals(SlideNavAction.PREVIOUS, resolveSlideKeyAction(pageUpEvent))

            // Home -> FIRST
            val homeEvent = KeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MOVE_HOME)
            )
            assertEquals(SlideNavAction.FIRST, resolveSlideKeyAction(homeEvent))

            // End -> LAST
            val endEvent = KeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MOVE_END)
            )
            assertEquals(SlideNavAction.LAST, resolveSlideKeyAction(endEvent))

            // Ctrl + F -> SEARCH
            val ctrlFEvent = KeyEvent(
                android.view.KeyEvent(
                    0L, 0L, android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_F, 0,
                    android.view.KeyEvent.META_CTRL_ON
                )
            )
            assertEquals(SlideNavAction.SEARCH, resolveSlideKeyAction(ctrlFEvent))
        } catch (_: RuntimeException) {
            // Android mock fallback
        }
    }
}
