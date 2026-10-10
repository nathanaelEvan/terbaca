package app.terbaca.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terbaca.core.ui.chrome.ToolbarIconButton

private const val HIGHLIGHT_ALPHA = 0.35f
private const val ACTIVE_HIGHLIGHT_ALPHA = 0.55f
private val SEARCH_HIGHLIGHT = Color(0xFFFFC107)
private val ACTIVE_SEARCH_HIGHLIGHT = Color(0xFFFF6D00)
private val MARKER_WIDTH = 6.dp
private val MARKER_HEIGHT = 3.dp

/**
 * Search in the toolbar (F-ACAD-5), like the iOS search field: the field, the hit counter,
 * previous/next and Done.
 */
@Composable
internal fun RowScope.SearchToolbarContent(
    query: String,
    onQueryChange: (String) -> Unit,
    hitCount: Int,
    activeHit: Int,
    searching: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 8.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.reader_search_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // Enter goes to the next hit and puts the keyboard away, so the page shows.
                    keyboard?.hide()
                    onNext()
                })
            )
        }
        if (query.trim().length >= MIN_QUERY_LENGTH) {
            Text(
                text = when {
                    hitCount == 0 && searching -> stringResource(R.string.reader_search_searching)
                    hitCount == 0 -> stringResource(R.string.reader_search_none)
                    activeHit >= 0 -> stringResource(R.string.reader_search_position, activeHit + 1, hitCount) +
                        if (searching) "+" else ""
                    else -> pluralStringResource(R.plurals.reader_search_count, hitCount, hitCount) +
                        if (searching) "+" else ""
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1
            )
        }
    }
    ToolbarIconButton(
        icon = Icons.Rounded.KeyboardArrowUp,
        contentDescription = stringResource(R.string.reader_search_previous),
        onClick = { if (hitCount > 0) onPrevious() }
    )
    ToolbarIconButton(
        icon = Icons.Rounded.KeyboardArrowDown,
        contentDescription = stringResource(R.string.reader_search_next),
        onClick = { if (hitCount > 0) onNext() }
    )
    Text(
        text = stringResource(R.string.reader_search_close),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClose)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    )
}

/** The hit list for the sidebar's Search tab: page and excerpt, match in bold. */
@Composable
internal fun SearchResultsList(
    hits: List<SearchHit>,
    activeHit: Int,
    searching: Boolean,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (hits.isEmpty()) {
            item {
                Text(
                    text = stringResource(if (searching) R.string.reader_search_searching else R.string.reader_search_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        items(hits.size) { i ->
            val hit = hits[i]
            val isActive = i == activeHit
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { selected = isActive }
                    .clickable { onClick(i) }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.reader_search_page, hit.pageIndex + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = buildAnnotatedString {
                        val end = hit.excerptMatchStart + hit.length
                        append(hit.excerpt.substring(0, hit.excerptMatchStart))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(hit.excerpt.substring(hit.excerptMatchStart, end))
                        }
                        append(hit.excerpt.substring(end))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * Highlights every hit on the visible pages, the active one stronger. Positions are read in the
 * draw phase only, so scrolling redraws this layer without recomposing (§4.6).
 */
@Composable
internal fun SearchHighlights(
    state: DocumentTransformState,
    hitsByPage: Map<Int, List<SearchHit>>,
    activeHit: SearchHit?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val s = state.scale
        if (s <= 0f || hitsByPage.isEmpty()) return@Canvas
        val visible = state.layout.pagesIn(state.getVisibleRectPoints())
        for (page in visible) {
            val hits = hitsByPage[page] ?: continue
            val pageRect = state.layout.pageRect(page)
            for (hit in hits) {
                val active = hit == activeHit
                for (r in hit.rects) {
                    drawRect(
                        color = if (active) ACTIVE_SEARCH_HIGHLIGHT else SEARCH_HIGHLIGHT,
                        topLeft = Offset((pageRect.left + r.left) * s + state.offsetX, (pageRect.top + r.top) * s + state.offsetY),
                        size = Size(r.width * s, r.height * s),
                        alpha = if (active) ACTIVE_HIGHLIGHT_ALPHA else HIGHLIGHT_ALPHA
                    )
                }
            }
        }
    }
}

/** Ticks on the right edge at each hit's position in the document (F-ACAD-5). */
@Composable
internal fun BoxScope.SearchMarkers(state: DocumentTransformState, hits: List<SearchHit>) {
    if (hits.isEmpty()) return
    val color = SEARCH_HIGHLIGHT
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .navigationBarsPadding()
            .fillMaxHeight()
            .width(MARKER_WIDTH)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val docHeight = state.layout.height
            if (docHeight <= 0f) return@Canvas
            val markerH = MARKER_HEIGHT.toPx()
            for (hit in hits) {
                val y = state.layout.pageRect(hit.pageIndex).top + (hit.rects.firstOrNull()?.top ?: 0f)
                val top = (y / docHeight * size.height).coerceIn(0f, size.height - markerH)
                drawRect(color, topLeft = Offset(0f, top), size = Size(size.width, markerH))
            }
        }
    }
}
