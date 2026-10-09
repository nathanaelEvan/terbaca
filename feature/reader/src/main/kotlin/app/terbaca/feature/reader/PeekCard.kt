package app.terbaca.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import kotlinx.coroutines.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An internal link's destination shown in the peek card (F-ACAD-3). */
data class PeekTarget(val pageIndex: Int, val pageY: Float?)

/** A tile request for the peek card's render, in device pixels of the page raster. */
internal data class PeekRegion(
    val fullWidth: Int,
    val fullHeight: Int,
    val startY: Int,
    val tileHeight: Int
)

/** The band shown starts this far above the destination, so a heading or label isn't cut. */
private const val PEEK_LEAD_POINTS = 6f

/** Height of the band shown, as a fraction of the page width (a bibliography entry, an equation). */
private const val PEEK_BAND_FRACTION = 0.3f

private const val MAX_PEEK_TILE_PX = 4096

/**
 * The region of [page] to render for a destination at [pageY] (page points from the top, or the
 * page top when null), [widthPx] wide: the full page width, from just above the destination,
 * a band [PEEK_BAND_FRACTION] of the page width tall, kept inside the page.
 */
internal fun peekRegion(page: PageInfo, pageY: Float?, widthPx: Int): PeekRegion {
    val width = widthPx.coerceIn(1, MAX_PEEK_TILE_PX)
    val scale = width / max(1f, page.widthPoints)
    val fullHeight = max(1, (page.heightPoints * scale).roundToInt())
    val bandPx = min(fullHeight, min(MAX_PEEK_TILE_PX, max(1, (page.widthPoints * PEEK_BAND_FRACTION * scale).roundToInt())))
    val y = pageY?.takeIf { it.isFinite() }?.let { (it - PEEK_LEAD_POINTS).coerceAtLeast(0f) } ?: 0f
    val startY = (y * scale).roundToInt().coerceIn(0, fullHeight - bandPx)
    return PeekRegion(width, fullHeight, startY, bandPx)
}

/**
 * Floating, non-modal card with a sharp render of an internal link's destination (F-ACAD-3):
 * the reading position doesn't move. "Go there" follows the link (with the back chip); the close
 * button dismisses it.
 */
@Composable
internal fun PeekCard(
    target: PeekTarget,
    page: PageInfo,
    renderer: PageRenderer?,
    docHandle: Long,
    onGoThere: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                val bitmap by produceState<Bitmap?>(null, target, widthPx, renderer, docHandle) {
                    value = renderPeek(renderer, docHandle, page, target, widthPx)
                }
                val img = bitmap
                if (img != null) {
                    Image(
                        bitmap = img.asImageBitmap(),
                        contentDescription = stringResource(R.string.reader_peek_description, page.index + 1),
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.reader_peek_page, page.index + 1),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.reader_peek_page, page.index + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_peek_close)) }
                TextButton(onClick = onGoThere) { Text(stringResource(R.string.reader_peek_go)) }
            }
        }
    }
}

private suspend fun renderPeek(
    renderer: PageRenderer?,
    docHandle: Long,
    page: PageInfo,
    target: PeekTarget,
    widthPx: Int
): Bitmap? {
    if (renderer == null || widthPx <= 0) return null
    val region = peekRegion(page, target.pageY, widthPx)
    return try {
        renderer.renderTile(
            docHandle = docHandle,
            pageIndex = page.index,
            fullWidth = region.fullWidth,
            fullHeight = region.fullHeight,
            startX = 0,
            startY = region.startY,
            tileWidth = region.fullWidth,
            tileHeight = region.tileHeight
        )
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        android.util.Log.w("PeekCard", "Peek render failed for page ${page.index}", e)
        null
    }
}
