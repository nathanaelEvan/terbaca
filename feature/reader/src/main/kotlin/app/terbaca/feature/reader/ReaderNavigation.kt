package app.terbaca.feature.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SCROLLBAR_HIDE_DELAY_MS = 1500L
private val THUMB_HEIGHT = 56.dp
private val THUMB_TOUCH_WIDTH = 48.dp // Material minimum touch target
private val THUMB_VISIBLE_WIDTH = 8.dp

// Keeps the thumb clear of the screen edge, where it is hard to grab and the system back swipe starts.
private val THUMB_EDGE_GAP = 12.dp

/**
 * Fast-scroll handle on the right edge (F-NAV-3). Shows while the document moves, hides after
 * [SCROLLBAR_HIDE_DELAY_MS]. Dragging it maps the thumb position to the whole document. The thumb
 * position is read in the layout phase, so scrolling doesn't recompose anything.
 */
@Composable
fun BoxScope.FastScrollbar(state: DocumentTransformState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var trackHeightPx by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    val scrollable by remember { derivedStateOf { state.layout.height * state.scale > state.viewportHeight + 1f } }

    LaunchedEffect(state) {
        // Each movement restarts the hide timer; the flag only flips on show/hide.
        snapshotFlow { state.offsetY }.drop(1).collectLatest {
            recentlyScrolled = true
            delay(SCROLLBAR_HIDE_DELAY_MS)
            recentlyScrolled = false
        }
    }
    val shown = scrollable && (recentlyScrolled || dragging)
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "scrollbar")
    if (!scrollable) return

    val thumbHeightPx = with(density) { THUMB_HEIGHT.toPx() }
    Box(
        modifier = modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .navigationBarsPadding()
            .fillMaxHeight()
            .padding(end = THUMB_EDGE_GAP)
            .width(THUMB_TOUCH_WIDTH)
            .onSizeChanged { trackHeightPx = it.height }
    ) {
        Box(
            modifier = Modifier
                .offset {
                    val travel = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                    IntOffset(0, (state.scrollFraction * travel).roundToInt())
                }
                .size(THUMB_TOUCH_WIDTH, THUMB_HEIGHT)
                .graphicsLayer { this.alpha = alpha }
                // Hidden thumb ignores touches, so it never steals a pan near the edge.
                .pointerInput(state, shown) {
                    if (!shown) return@pointerInput
                    var grabFraction = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            grabFraction = state.scrollFraction
                            scope.launch { state.stopFling() }
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            val travel = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
                            grabFraction = (grabFraction + dy / travel).coerceIn(0f, 1f)
                            state.scrollToFraction(grabFraction)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(THUMB_VISIBLE_WIDTH)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50))
            )
        }
    }
}

/**
 * "12 / 30" pill at the bottom (F-NAV-2). Recomposes only when the page changes. Tapping it opens
 * a dialog to jump to a page number.
 */
@Composable
fun BoxScope.PageIndicator(state: DocumentTransformState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val currentPage by remember(state) { derivedStateOf { state.currentPage } }
    var showJump by remember { mutableStateOf(false) }
    val pageCount = state.layout.pageCount

    Surface(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .alpha(0.9f),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Text(
            text = stringResource(R.string.reader_page_indicator, currentPage + 1, pageCount),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clickable { showJump = true }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }

    if (showJump) {
        JumpToPageDialog(
            pageCount = pageCount,
            onDismiss = { showJump = false },
            onJump = { page ->
                showJump = false
                scope.launch { state.scrollToPage(page - 1) }
            }
        )
    }
}

/** Parses a 1-based page number typed by the user; null unless it is in 1..[pageCount]. */
internal fun parsePageNumber(input: String, pageCount: Int): Int? =
    input.trim().toIntOrNull()?.takeIf { it in 1..pageCount }

@Composable
private fun JumpToPageDialog(pageCount: Int, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = parsePageNumber(text, pageCount)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_jump_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter(Char::isDigit).take(pageCount.toString().length) },
                label = { Text(stringResource(R.string.reader_jump_label, pageCount)) },
                isError = text.isNotEmpty() && page == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { page?.let(onJump) }),
                modifier = Modifier.focusRequester(focus)
            )
        },
        confirmButton = {
            TextButton(onClick = { page?.let(onJump) }, enabled = page != null) {
                Text(stringResource(R.string.reader_jump_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_jump_cancel)) }
        }
    )
}
