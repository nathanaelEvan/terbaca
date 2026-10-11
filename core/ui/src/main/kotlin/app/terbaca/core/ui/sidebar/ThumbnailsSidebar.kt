package app.terbaca.core.ui.sidebar

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.terbaca.core.model.OutlineItem
import app.terbaca.core.model.PageInfo
import app.terbaca.core.ui.chrome.ToolbarHeight
import app.terbaca.core.ui.R

private const val THUMBNAIL_WIDTH_FRACTION = 0.85f
private const val TAB_THUMBNAILS = 0
private const val TAB_OUTLINE = 1
private const val TAB_SEARCH = 2
private val OUTLINE_INDENT = 12.dp

/**
 * Collapsible sidebar with page thumbnails and, when the document has bookmarks, its outline
 * (F-NAV-4, M2-06, M3-02). Docked beside the document in landscape; overlay drawer in portrait.
 */
@Composable
fun ThumbnailsSidebar(
    pages: List<PageInfo>,
    currentPage: Int,
    onPageClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    thumbnailLoader: (suspend (PageInfo) -> Bitmap?)? = null,
    outline: List<OutlineItem> = emptyList(),
    onOutlineClick: (OutlineItem) -> Unit = {},
    searchResults: (@Composable (Modifier) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    // The search tab opens itself when a search starts; the user can still switch away.
    var selectedTab by rememberSaveable(searchResults != null) {
        mutableIntStateOf(if (searchResults != null) TAB_SEARCH else TAB_THUMBNAILS)
    }
    val tabs = listOfNotNull(
        TAB_THUMBNAILS,
        TAB_OUTLINE.takeIf { outline.isNotEmpty() },
        TAB_SEARCH.takeIf { searchResults != null }
    )
    val tab = selectedTab.takeIf { it in tabs } ?: TAB_THUMBNAILS
    val outlineRows = remember(outline) { flattenOutline(outline) }

    LaunchedEffect(currentPage) {
        if (currentPage in pages.indices) {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            // Off-screen items haven't been measured; thumbnails are similar in size, so borrow one.
            val itemSize = (visibleItems.firstOrNull { it.index == currentPage } ?: visibleItems.firstOrNull())?.size ?: 0
            val offset = centeredScrollOffset(
                viewportSize = layoutInfo.viewportSize.height,
                itemSize = itemSize,
                beforeContentPadding = layoutInfo.beforeContentPadding
            )
            listState.animateScrollToItem(currentPage, offset)
        }
    }

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Opens below the toolbar, which stays up while the sidebar is open.
                .statusBarsPadding()
                .padding(top = ToolbarHeight)
                .navigationBarsPadding()
        ) {
            if (tabs.size > 1) {
                SegmentedControl(
                    options = tabs.map { t ->
                        stringResource(
                            when (t) {
                                TAB_OUTLINE -> R.string.reader_tab_outline
                                TAB_SEARCH -> R.string.reader_tab_search
                                else -> R.string.reader_tab_thumbnails
                            }
                        )
                    },
                    selectedIndex = tabs.indexOf(tab),
                    onSelect = { selectedTab = tabs[it] },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }

            if (tab == TAB_SEARCH && searchResults != null) {
                searchResults(Modifier.fillMaxWidth().weight(1f))
            } else if (tab == TAB_OUTLINE) {
                OutlineList(
                    rows = outlineRows,
                    currentPage = currentPage,
                    onClick = onOutlineClick,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(pages.size, key = { it }) { pageIndex ->
                    val page = pages[pageIndex]
                    val isCurrent = pageIndex == currentPage

                    ThumbnailItem(
                        page = page,
                        aspectRatio = page.aspectRatio,
                        isCurrent = isCurrent,
                        onClick = { onPageClick(pageIndex) },
                        thumbnailLoader = thumbnailLoader
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbnailItem(
    page: PageInfo,
    aspectRatio: Float,
    isCurrent: Boolean,
    onClick: () -> Unit,
    thumbnailLoader: (suspend (PageInfo) -> Bitmap?)?,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, page.index) {
        value = thumbnailLoader?.invoke(page)
    }

    val pageLabel = stringResource(R.string.reader_thumbnail_page, page.index + 1)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // One focusable button per page, announced as "Page N" and whether it's the current one.
            .semantics(mergeDescendants = true) {
                contentDescription = pageLabel
                selected = isCurrent
            }
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(THUMBNAIL_WIDTH_FRACTION)
                .aspectRatio(aspectRatio),
            shape = RoundedCornerShape(6.dp),
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            border = if (isCurrent) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
            tonalElevation = if (isCurrent) 4.dp else 1.dp
        ) {
            val img = bitmap
            if (img != null) {
                Image(
                    bitmap = img.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${page.index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${page.index + 1}",
            modifier = Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun OutlineList(
    rows: List<OutlineRow>,
    currentPage: Int,
    onClick: (OutlineItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // The entry for the current position: the last one that starts on or before the current page.
    val currentRow = rows.indexOfLast { it.item.pageIndex in 0..currentPage }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(rows.size) { i ->
            val row = rows[i]
            val isCurrent = i == currentRow
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { selected = isCurrent }
                    .clickable(enabled = row.item.pageIndex >= 0, role = Role.Button) { onClick(row.item) }
                    .padding(start = 16.dp + OUTLINE_INDENT * row.depth, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.item.title,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    style = if (row.depth == 0) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (row.item.pageIndex >= 0) {
                    Text(
                        text = "${row.item.pageIndex + 1}",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** An iOS-style segmented control: equal segments, the selected one raised. */
@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                    .semantics { this.selected = selected }
                    .clickable(role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** An outline entry with its nesting [depth] (0 = top level), in reading order. */
internal data class OutlineRow(val item: OutlineItem, val depth: Int)

/** Flattens the outline tree depth-first, so nested entries follow their parent. */
internal fun flattenOutline(items: List<OutlineItem>): List<OutlineRow> {
    val rows = ArrayList<OutlineRow>()
    fun visit(list: List<OutlineItem>, depth: Int) {
        for (item in list) {
            rows.add(OutlineRow(item, depth))
            visit(item.children, depth + 1)
        }
    }
    visit(items, 0)
    return rows
}

/**
 * Scroll offset for [androidx.compose.foundation.lazy.LazyListState.animateScrollToItem] that
 * centres an item of [itemSize] px in a viewport of [viewportSize] px. The offset is measured from
 * the end of the [beforeContentPadding]; negative values leave space above the item.
 */
internal fun centeredScrollOffset(viewportSize: Int, itemSize: Int, beforeContentPadding: Int): Int =
    beforeContentPadding - ((viewportSize - itemSize) / 2).coerceAtLeast(0)
