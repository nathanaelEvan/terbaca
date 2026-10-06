package app.terbaca.feature.reader

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.cache.ThumbnailPipeline
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val SIDEBAR_WIDTH = 240.dp
private const val SCRIM_ALPHA = 0.5f

@Composable
fun ReaderScreen(
    pages: List<PageInfo>,
    modifier: Modifier = Modifier,
    renderer: PageRenderer? = null,
    docHandle: Long = 0L,
    initialPage: Int = 0,
    contentHash: String? = null,
    thumbnailPipeline: ThumbnailPipeline? = null,
    thumbnailLoader: (suspend (PageInfo) -> Bitmap?)? = remember(thumbnailPipeline, docHandle, contentHash) {
        if (thumbnailPipeline != null && contentHash != null) {
            { page -> thumbnailPipeline.getThumbnail(docHandle, contentHash, page) }
        } else {
            null
        }
    },
    onPageChange: ((Int) -> Unit)? = null,
    onModeToggle: (() -> Unit)? = null,
    onSearchRequested: (() -> Unit)? = null
) {
    val layout = remember(pages) { DocumentLayout(pages) }
    val state = rememberDocumentTransformState(layout, initialPage = initialPage)
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var isSidebarOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(state) {
        snapshotFlow { state.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                onPageChange?.invoke(page)
            }
    }

    if (isSidebarOpen && !isLandscape) {
        BackHandler { isSidebarOpen = false }
    }

    @Composable
    fun DocumentSurfaceContent(surfaceModifier: Modifier) {
        Box(modifier = surfaceModifier) {
            DocumentSurface(
                state = state,
                renderer = renderer,
                docHandle = docHandle
            )
            FastScrollbar(state)
            PageIndicator(state)

            if (!isSidebarOpen) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(top = 16.dp, start = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = stringResource(R.string.reader_sidebar_toggle),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { isSidebarOpen = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            if (onModeToggle != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp, end = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = "Slides",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onModeToggle() }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                handleReaderKeyEvent(event, state, onSearch = onSearchRequested)
            }
    ) {
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (isSidebarOpen) {
                    ThumbnailsSidebar(
                        pages = pages,
                        currentPage = state.currentPage,
                        onPageClick = { page -> scope.launch { state.scrollToPage(page) } },
                        onClose = { isSidebarOpen = false },
                        thumbnailLoader = thumbnailLoader,
                        modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight()
                    )
                }
                DocumentSurfaceContent(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            DocumentSurfaceContent(Modifier.fillMaxSize())
            if (isSidebarOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                        .clickable { isSidebarOpen = false }
                )
                ThumbnailsSidebar(
                    pages = pages,
                    currentPage = state.currentPage,
                    onPageClick = { page ->
                        isSidebarOpen = false
                        scope.launch { state.scrollToPage(page) }
                    },
                    onClose = { isSidebarOpen = false },
                    thumbnailLoader = thumbnailLoader,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(SIDEBAR_WIDTH)
                        .fillMaxHeight()
                )
            }
        }
    }
}
