package app.terbaca.core.ui.chrome

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.terbaca.core.ui.theme.TerbacaChrome
import app.terbaca.core.ui.theme.TerbacaDarkAccent
import app.terbaca.core.ui.theme.TerbacaDarkBorder
import app.terbaca.core.ui.theme.TerbacaDarkSurfaceHigh
import app.terbaca.core.ui.theme.TerbacaDarkTextPrimary

/** Height of the toolbar row, below the status bar; it only grows when a large font needs it. */
val ToolbarHeight = 52.dp

// The 48 dp minimum touch target from the Android accessibility guidelines.
private val ToolbarButtonSize = 48.dp
private val ToolbarIconSize = 22.dp

/** Left and right insets the chrome keeps clear of: cutouts and a side navigation bar. */
private val SideInsets: WindowInsets
    @Composable get() = WindowInsets.displayCutout.union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal)

/**
 * The reader's top bar, after Preview and PDF Expert: [leading] buttons, the document [title],
 * then [trailing] buttons. It extends under the status bar so the two read as one strip, and it
 * slides away with the status bar when [visible] turns false.
 */
@Composable
fun TopToolbar(
    visible: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    center: (@Composable RowScope.() -> Unit)? = null
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(TerbacaChrome)) {
            Box(Modifier.fillMaxWidth().statusBarsPadding())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ToolbarHeight)
                    .windowInsetsPadding(SideInsets)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                leading()
                if (center != null) {
                    center()
                } else {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() },
                        color = TerbacaDarkTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        // Cut in the middle, like Finder: a long file name keeps its ".pptx.pdf".
                        overflow = TextOverflow.MiddleEllipsis
                    )
                }
                trailing()
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(TerbacaDarkBorder))
        }
    }
}

/** A toolbar icon button; [selected] marks a toggle that's on (sidebar open, settings shown). */
@Composable
fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    ToolbarButton(contentDescription, onClick, modifier, selected) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) TerbacaDarkAccent else TerbacaDarkTextPrimary,
            modifier = Modifier.size(ToolbarIconSize)
        )
    }
}

/** The "Aa" view-settings button, as Books and PDF Expert draw it. */
@Composable
fun ToolbarTextSettingsButton(
    contentDescription: String,
    onClick: () -> Unit,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    ToolbarButton(contentDescription, onClick, modifier, selected) {
        Text(
            text = "aA",
            // The button's own description is read instead of "aA".
            modifier = Modifier.clearAndSetSemantics { },
            color = if (selected) TerbacaDarkAccent else TerbacaDarkTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ToolbarButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    selected: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(ToolbarButtonSize)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) TerbacaDarkSurfaceHigh else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * A small floating pill over the page: the page number, the back-to-page chip. A tappable pill
 * reserves a 48 dp touch target around itself; [onClickLabel] tells TalkBack what a tap does.
 */
@Composable
fun FloatingPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(50)
    // The click sits on the Surface's own node, together with any semantics in [modifier], so
    // TalkBack reads a caller's contentDescription and offers the tap as one stop.
    // Wrap in Box with minimumInteractiveComponentSize so the 48 dp touch target does not
    // inflate the visual Surface height from 36 dp to 48 dp (fixing the ~6 dp shift from #68).
    Box(
        modifier = modifier.then(
            if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier
        ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = if (onClick != null) {
                Modifier
                    .clip(shape)
                    .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            } else {
                Modifier
            },
            shape = shape,
            color = TerbacaChrome,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}

/**
 * Hides the status and navigation bars while [hidden], so the page fills the whole screen; they
 * come back with a swipe from the edge, and when [hidden] turns false or this leaves composition.
 * The content is laid out edge to edge either way, so hiding them never resizes the page.
 */
@Composable
fun SystemBarsEffect(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val controller = view.context.findActivity()?.let { WindowCompat.getInsetsController(it.window, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Calls [onDrag] once per gesture, when a finger moves past the touch slop. Watches the initial
 * pass without consuming anything, so the gesture still pans or pages the content as before.
 */
fun Modifier.onDragStarted(onDrag: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                onDrag()
                break
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
