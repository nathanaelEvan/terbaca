package app.terbaca.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.terbaca.core.model.PageRect
import kotlin.math.roundToInt

private const val SELECTION_ALPHA = 0.3f
private val HANDLE_SIZE = 24.dp

/**
 * The selection highlight and its two drag handles (F-ACAD-6). Positions are read in the draw and
 * layout phases, so scrolling with a selection doesn't recompose (§4.6). Dragging a handle calls
 * [onMoveHandle] with which handle moved and the character index under it.
 */
@Composable
internal fun SelectionLayer(
    state: DocumentTransformState,
    selection: TextSelection,
    onMoveHandle: (movingStart: Boolean, index: Int) -> Unit
) {
    val rects = remember(selection) {
        matchRects(selection.page.charBoxes, selection.start, selection.end - selection.start)
    }
    if (rects.isEmpty()) return
    val color = MaterialTheme.colorScheme.primary
    val pageIndex = selection.page.pageIndex
    Canvas(modifier = Modifier.fillMaxSize()) {
        val s = state.scale
        val page = state.layout.pageRect(pageIndex)
        for (r in rects) {
            drawRect(
                color = color,
                topLeft = Offset((page.left + r.left) * s + state.offsetX, (page.top + r.top) * s + state.offsetY),
                size = Size(r.width * s, r.height * s),
                alpha = SELECTION_ALPHA
            )
        }
    }
    SelectionHandle(state, pageIndex, rects.first(), isStart = true, selection, onMoveHandle)
    SelectionHandle(state, pageIndex, rects.last(), isStart = false, selection, onMoveHandle)
}

@Composable
private fun SelectionHandle(
    state: DocumentTransformState,
    pageIndex: Int,
    rect: PageRect,
    isStart: Boolean,
    selection: TextSelection,
    onMoveHandle: (Boolean, Int) -> Unit
) {
    val handlePx = with(LocalDensity.current) { HANDLE_SIZE.toPx() }
    val currentSelection by rememberUpdatedState(selection)
    // The handle hangs below the selection's first character (start) or last character (end).
    fun anchor(): Offset {
        val page = state.layout.pageRect(pageIndex)
        val x = page.left + if (isStart) rect.left else rect.right
        return Offset(x * state.scale + state.offsetX, (page.top + rect.bottom) * state.scale + state.offsetY)
    }
    Box(
        modifier = Modifier
            .offset {
                val a = anchor()
                IntOffset((a.x - handlePx / 2).roundToInt(), a.y.roundToInt())
            }
            .size(HANDLE_SIZE)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(state, isStart, pageIndex) {
                var position = Offset.Zero
                detectDragGestures(
                    onDragStart = { position = anchor() - Offset(0f, handlePx / 2) },
                    onDrag = { change, delta ->
                        change.consume()
                        position += delta
                        val point = state.pagePointAt(position) ?: return@detectDragGestures
                        if (point.pageIndex != pageIndex) return@detectDragGestures
                        val index = charIndexAt(currentSelection.page.charBoxes, point.x, point.y)
                            ?: return@detectDragGestures
                        onMoveHandle(isStart, index)
                    }
                )
            }
    )
}

/** Copies [text] to the clipboard. */
internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Terbaca", text))
}
