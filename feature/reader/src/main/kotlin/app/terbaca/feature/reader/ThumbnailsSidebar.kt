package app.terbaca.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.terbaca.core.model.PageInfo

private const val THUMBNAIL_WIDTH_FRACTION = 0.85f

/**
 * Collapsible sidebar displaying document page thumbnails (F-NAV-4, M2-06).
 * Docked beside the document in landscape; overlay drawer in portrait.
 */
@Composable
fun ThumbnailsSidebar(
    pages: List<PageInfo>,
    currentPage: Int,
    onPageClick: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailLoader: (suspend (PageInfo) -> Bitmap?)? = null
) {
    val listState = rememberLazyListState()

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
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrimaryTabRow(
                    selectedTabIndex = 0,
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Tab(
                        selected = true,
                        onClick = { },
                        text = {
                            Text(
                                text = stringResource(R.string.reader_tab_thumbnails),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.reader_sidebar_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
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

/**
 * Scroll offset for [androidx.compose.foundation.lazy.LazyListState.animateScrollToItem] that
 * centres an item of [itemSize] px in a viewport of [viewportSize] px. The offset is measured from
 * the end of the [beforeContentPadding]; negative values leave space above the item.
 */
internal fun centeredScrollOffset(viewportSize: Int, itemSize: Int, beforeContentPadding: Int): Int =
    beforeContentPadding - ((viewportSize - itemSize) / 2).coerceAtLeast(0)
