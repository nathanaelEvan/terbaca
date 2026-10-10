package app.terbaca.feature.library

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terbaca.core.data.RecentDocument
import app.terbaca.core.data.RecentEntry

private const val PAGE_ASPECT_RATIO = 0.77f // letter/A4 portrait default
private const val UNAVAILABLE_ALPHA = 0.5f

/**
 * F-LIB-1: recents grid with first-page thumbnail and document metadata, laid out like a
 * bookshelf: covers keep their own shape and sit on a common baseline.
 */
@Composable
fun LibraryScreen(
    recents: List<RecentEntry>,
    onOpenDocument: () -> Unit,
    onRecentClick: (RecentDocument) -> Unit,
    onRemove: (RecentDocument) -> Unit,
    modifier: Modifier = Modifier,
    thumbnailLoader: (suspend (contentHash: String) -> Bitmap?)? = null,
    thumbnailVersion: Int = 0
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                OpenButton(onOpenDocument)
            }
            if (recents.isEmpty()) {
                EmptyLibrary(onOpenDocument)
            } else {
                Text(
                    text = stringResource(R.string.library_recents),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 168.dp),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    items(recents, key = { it.document.uri }) { entry ->
                        RecentCard(entry, onRecentClick, onRemove, thumbnailLoader, thumbnailVersion)
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.library_open),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EmptyLibrary(onOpenDocument: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Outlined.LibraryBooks,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.library_empty_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.library_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            OpenButton(onOpenDocument)
        }
    }
}

@Composable
private fun RecentCard(
    entry: RecentEntry,
    onRecentClick: (RecentDocument) -> Unit,
    onRemove: (RecentDocument) -> Unit,
    thumbnailLoader: (suspend (contentHash: String) -> Bitmap?)?,
    thumbnailVersion: Int
) {
    val doc = entry.document
    var thumbnailBitmap by remember(doc.contentHash) { mutableStateOf<Bitmap?>(null) }
    // A thumbnail is often written after the card first shows (it renders once the document is
    // open), so retry a missing one whenever the cache reports a new write.
    LaunchedEffect(doc.contentHash, thumbnailVersion) {
        if (thumbnailBitmap == null) thumbnailBitmap = thumbnailLoader?.invoke(doc.contentHash)
    }

    Column(
        modifier = Modifier
            .then(if (entry.isAvailable) Modifier.clickable { onRecentClick(doc) } else Modifier)
            .alpha(if (entry.isAvailable) 1f else UNAVAILABLE_ALPHA)
    ) {
        // A portrait-shaped slot; the cover keeps its own shape inside it, on the bottom edge, so
        // a row of papers and decks shares one baseline like books on a shelf.
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(PAGE_ASPECT_RATIO),
            contentAlignment = Alignment.BottomCenter
        ) {
            val bitmap = thumbnailBitmap
            val coverAspect = bitmap?.let { it.width.toFloat() / it.height } ?: PAGE_ASPECT_RATIO
            Surface(
                modifier = Modifier.fillMaxWidth().aspectRatio(coverAspect.coerceAtLeast(PAGE_ASPECT_RATIO)),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = doc.displayName,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            // Cut in the middle, like Finder: a long file name keeps its ".pptx.pdf".
            overflow = TextOverflow.MiddleEllipsis
        )
        Spacer(Modifier.height(2.dp))
        if (entry.isAvailable) {
            Text(
                text = stringResource(R.string.library_page_of, doc.lastPage + 1, doc.pageCount) + " · " +
                    DateUtils.getRelativeTimeSpanString(doc.lastOpenedAtMillis),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.library_unavailable),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onRemove(doc) }) { Text(stringResource(R.string.library_remove)) }
            }
        }
    }
}
