package app.terbaca.feature.slides

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terbaca.core.model.PageInfo
import app.terbaca.core.ui.theme.TerbacaDarkAccent
import app.terbaca.core.ui.theme.TerbacaDarkBorder
import app.terbaca.core.ui.theme.TerbacaDarkTextSecondary

private const val MIN_GRID_COLUMNS = 3
private const val MAX_GRID_COLUMNS = 5
private val GRID_CELL_TARGET_WIDTH = 220.dp
private val GRID_PADDING = 16.dp

/** Columns for a grid [width] wide (F-DECK-1): about one per 220 dp, between 3 and 5. */
fun gridColumns(width: Dp): Int =
    (width / GRID_CELL_TARGET_WIDTH).toInt().coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)

/**
 * Grid overview of every slide (F-DECK-1), scrolled to [currentPage]. [cellModifier] lets the
 * caller attach the shared-element transition to a cell's image.
 */
@Composable
internal fun SlideGrid(
    pages: List<PageInfo>,
    currentPage: Int,
    thumbnailLoader: (suspend (PageInfo) -> Bitmap?)?,
    onSlideClick: (Int) -> Unit,
    cellModifier: @Composable (Int) -> Modifier,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = gridColumns(maxWidth)
        val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = currentPage.coerceIn(0, pages.lastIndex))
        // The grid is full-screen: keep the cells clear of the status and navigation bars.
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val direction = LocalLayoutDirection.current
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = insets.calculateStartPadding(direction) + GRID_PADDING,
                top = insets.calculateTopPadding() + GRID_PADDING,
                end = insets.calculateEndPadding(direction) + GRID_PADDING,
                bottom = insets.calculateBottomPadding() + GRID_PADDING
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pages.size, key = { it }) { index ->
                GridCell(
                    page = pages[index],
                    isCurrent = index == currentPage,
                    thumbnailLoader = thumbnailLoader,
                    imageModifier = cellModifier(index),
                    onClick = { onSlideClick(index) }
                )
            }
        }
    }
}

@Composable
private fun GridCell(
    page: PageInfo,
    isCurrent: Boolean,
    thumbnailLoader: (suspend (PageInfo) -> Bitmap?)?,
    imageModifier: Modifier,
    onClick: () -> Unit
) {
    val bitmap by produceState<Bitmap?>(null, page.index) { value = thumbnailLoader?.invoke(page) }
    val label = stringResource(R.string.slides_slide_description, page.index + 1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { selected = isCurrent }
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(page.aspectRatio),
            shape = RoundedCornerShape(6.dp),
            color = Color.White,
            border = if (isCurrent) BorderStroke(3.dp, TerbacaDarkAccent) else BorderStroke(1.dp, TerbacaDarkBorder)
        ) {
            val img = bitmap
            if (img != null && !img.isRecycled) {
                Image(
                    bitmap = img.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    modifier = imageModifier.fillMaxSize()
                )
            } else {
                Box(modifier = imageModifier.fillMaxSize())
            }
        }
        Text(
            text = "${page.index + 1}",
            color = if (isCurrent) TerbacaDarkAccent else TerbacaDarkTextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
