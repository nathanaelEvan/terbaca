package app.terbaca.feature.library

import android.graphics.Bitmap
import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.terbaca.core.data.RecentDocument
import app.terbaca.core.data.RecentEntry

private const val PAGE_ASPECT_RATIO = 0.77f // letter/A4 portrait default
private const val UNAVAILABLE_ALPHA = 0.5f

/**
 * F-LIB-1: recents grid with first-page thumbnail and document metadata.
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onOpenDocument) { Text(stringResource(R.string.library_open)) }
            }
            if (recents.isEmpty()) {
                EmptyLibrary()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 180.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
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
private fun EmptyLibrary() {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.library_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(PAGE_ASPECT_RATIO),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            val bitmap = thumbnailBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = doc.displayName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (entry.isAvailable) {
            Text(
                text = stringResource(R.string.library_page_of, doc.lastPage + 1, doc.pageCount) + " · " +
                    DateUtils.getRelativeTimeSpanString(doc.lastOpenedAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.library_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onRemove(doc) }) { Text(stringResource(R.string.library_remove)) }
            }
        }
    }
}
