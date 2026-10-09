package app.terbaca.feature.slides

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresentationTest {

    @Test
    fun presentationKeyAction_volumeKeysFlipAndEscapeExits() {
        assertEquals(SlideNavAction.NEXT, presentationKeyAction(Key.VolumeDown))
        assertEquals(SlideNavAction.PREVIOUS, presentationKeyAction(Key.VolumeUp))
        assertEquals(SlideNavAction.EXIT_PRESENTATION, presentationKeyAction(Key.Escape))
        assertEquals(SlideNavAction.NONE, presentationKeyAction(Key.A))
    }

    @Test
    fun presentationTapAction_thirds() {
        assertEquals(SlideNavAction.PREVIOUS, presentationTapAction(100f, 1200f))
        assertEquals(SlideNavAction.NONE, presentationTapAction(600f, 1200f))
        assertEquals(SlideNavAction.NEXT, presentationTapAction(1100f, 1200f))
        assertEquals(SlideNavAction.NONE, presentationTapAction(10f, 0f))
    }

    @Test
    fun exitPresentation_neverMovesThePager() {
        assertNull(slideNavTargetPage(SlideNavAction.EXIT_PRESENTATION, 3, 10))
    }
}
