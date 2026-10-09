package app.terbaca.feature.slides

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Keys that only act in presentation mode (F-DECK-2): the volume keys flip slides, as many
 * presenter remotes send them, and Escape leaves. Outside presentation mode they keep their usual
 * meaning, so the volume keys still change the volume while reading.
 */
fun presentationKeyAction(key: Key): SlideNavAction = when (key) {
    Key.VolumeDown -> SlideNavAction.NEXT
    Key.VolumeUp -> SlideNavAction.PREVIOUS
    Key.Escape -> SlideNavAction.EXIT_PRESENTATION
    else -> SlideNavAction.NONE
}

/**
 * Tap zones in presentation mode (F-DECK-2): the left third goes back, the right third forward,
 * the middle third does nothing, so a stray tap while presenting doesn't flip a slide.
 */
fun presentationTapAction(x: Float, width: Float): SlideNavAction = when {
    width <= 0f -> SlideNavAction.NONE
    x < width / 3f -> SlideNavAction.PREVIOUS
    x > width * 2f / 3f -> SlideNavAction.NEXT
    else -> SlideNavAction.NONE
}

/**
 * While composed, keeps the screen on (F-DECK-3) and hides the system bars; both are restored
 * when presentation mode ends. The bars come back with a swipe from the edge.
 */
@Composable
internal fun PresentationWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        val controller = view.context.findActivity()?.let { WindowCompat.getInsetsController(it.window, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
