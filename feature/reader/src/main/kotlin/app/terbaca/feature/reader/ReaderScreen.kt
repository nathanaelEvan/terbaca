package app.terbaca.feature.reader

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.terbaca.core.model.OutlineItem
import app.terbaca.core.model.PageColumnLayout
import app.terbaca.core.model.PageInfo
import app.terbaca.core.model.PageLink
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.cache.ThumbnailPipeline
import app.terbaca.core.ui.sidebar.ThumbnailsSidebar
import app.terbaca.feature.reader.column.ColumnDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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
    initialViewport: ReaderViewport? = null,
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
    onViewportChange: ((ReaderViewport) -> Unit)? = null,
    onModeToggle: (() -> Unit)? = null,
    onSearchRequested: (() -> Unit)? = null
) {
    val layout = remember(pages) { DocumentLayout(pages) }
    val state = rememberDocumentTransformState(layout, initialPage, initialViewport)
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var isSidebarOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // Outline and links (F-NAV-4/5/6, M3-02). Links are read per page on the first tap there.
    val outline by produceState(emptyList<OutlineItem>(), renderer, docHandle) {
        value = renderer?.let { r -> extractOrEmpty { r.getOutline(docHandle) } }.orEmpty()
    }
    val pageLinks = remember(renderer, docHandle) { HashMap<Int, List<PageLink>>() }
    val jumpHistory = remember(docHandle) { JumpHistory() }
    var externalUri by remember { mutableStateOf<String?>(null) }
    var peek by remember(docHandle) { mutableStateOf<PeekTarget?>(null) }

    // Column-aware double-tap zoom (F-ACAD-1): columns are detected once per page, on its first
    // double-tap. The position before a double-tap zoom is kept, so the next double-tap returns.
    val pageColumns = remember(renderer, docHandle) { HashMap<Int, PageColumnLayout>() }
    var beforeDoubleTap by remember(docHandle) { mutableStateOf<ReaderViewport?>(null) }

    // Following a link or outline entry saves the position first, for the back chip.
    val jumpTo: (Int, Float?) -> Unit = { page, pageY ->
        jumpHistory.push(JumpEntry(state.viewport, state.currentPage))
        scope.launch { state.scrollToDestination(page, pageY) }
    }
    val onOutlineClick: (OutlineItem) -> Unit = { item ->
        if (item.pageIndex >= 0) jumpTo(item.pageIndex, item.destY)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Both wait for the first real viewport: before it, the position reads as page 1 at fit-width.
    LaunchedEffect(state) {
        snapshotFlow { if (state.hasViewport) state.currentPage else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { page ->
                onPageChange?.invoke(page)
            }
    }

    // Fires every frame during a gesture; the receiver debounces (F-LIB-2).
    LaunchedEffect(state) {
        snapshotFlow { if (state.hasViewport) state.viewport else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { viewport ->
                onViewportChange?.invoke(viewport)
            }
    }

    if (isSidebarOpen && !isLandscape) {
        BackHandler { isSidebarOpen = false }
    }

    // Only changes when the page does; reading state.currentPage here recomposed every scroll frame.
    val currentPage by remember(state) { derivedStateOf { state.currentPage } }

    // System bars inset the whole reader, like the library screen, so pages never draw under them.
    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                handleReaderKeyEvent(event, state, onSearch = onSearchRequested)
            }
    ) {
        // One layout for both orientations, so the document surface keeps its composition position
        // (and its tile cache) on rotation: docked sidebar in this Row, portrait overlay below.
        Row(modifier = Modifier.fillMaxSize()) {
            if (isLandscape && isSidebarOpen) {
                ThumbnailsSidebar(
                    pages = pages,
                    currentPage = currentPage,
                    onPageClick = { page -> scope.launch { state.scrollToPage(page) } },
                    onClose = { isSidebarOpen = false },
                    thumbnailLoader = thumbnailLoader,
                    outline = outline,
                    onOutlineClick = onOutlineClick,
                    modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight()
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // A tap that isn't a drag follows the link under it (the surface consumes drags).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(state, renderer, docHandle) {
                            detectTapGestures(onDoubleTap = { position ->
                                val previous = beforeDoubleTap
                                if (previous != null) {
                                    beforeDoubleTap = null
                                    scope.launch { state.restoreViewport(previous, animated = true) }
                                    return@detectTapGestures
                                }
                                val point = state.pagePointAt(position) ?: return@detectTapGestures
                                beforeDoubleTap = state.viewport
                                scope.launch {
                                    val page = pages[point.pageIndex]
                                    val columns = pageColumns[point.pageIndex] ?: renderer?.let { r ->
                                        val boxes = extractOrEmpty { r.getPageText(docHandle, point.pageIndex).charBoxes }
                                        ColumnDetector.detect(point.pageIndex, page.widthPoints, page.heightPoints, boxes)
                                            .also { pageColumns[point.pageIndex] = it }
                                    }
                                    val column = columns?.findColumn(point.x, point.y)
                                    if (column != null) {
                                        state.zoomToSpan(point.pageIndex, column.left, column.right, point.y, position.y)
                                    } else {
                                        // Full-width region (title, abstract, wide figure): fit the page width.
                                        state.zoomToSpan(
                                            point.pageIndex, 0f, page.widthPoints, point.y, position.y, marginPoints = 0f
                                        )
                                    }
                                }
                            }, onTap = { position ->
                                val r = renderer ?: return@detectTapGestures
                                val point = state.pagePointAt(position) ?: return@detectTapGestures
                                scope.launch {
                                    val links = pageLinks[point.pageIndex]
                                        ?: extractOrEmpty { r.getPageLinks(docHandle, point.pageIndex) }
                                            .also { pageLinks[point.pageIndex] = it }
                                    val link = linkAt(links, point.x, point.y) ?: return@launch
                                    if (link.isInternal) {
                                        // Peek first (F-ACAD-3); "Go there" on the card follows the link.
                                        if (link.targetPageIndex in pages.indices) {
                                            peek = PeekTarget(link.targetPageIndex, link.targetY)
                                        }
                                    } else {
                                        externalUri = link.uri
                                    }
                                }
                            })
                        }
                ) {
                    DocumentSurface(
                        state = state,
                        renderer = renderer,
                        docHandle = docHandle
                    )
                }
                FastScrollbar(state)
                PageIndicator(state)

                peek?.let { target ->
                    PeekCard(
                        target = target,
                        page = pages[target.pageIndex],
                        renderer = renderer,
                        docHandle = docHandle,
                        onGoThere = {
                            peek = null
                            jumpTo(target.pageIndex, target.pageY)
                        },
                        onDismiss = { peek = null },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                jumpHistory.latest?.let { entry ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        tonalElevation = 2.dp
                    ) {
                        Text(
                            text = stringResource(R.string.reader_back_to_page, entry.page + 1),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable {
                                    jumpHistory.pop()?.let { scope.launch { state.restoreViewport(it.viewport) } }
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }

                if (!isSidebarOpen) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
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
                            .padding(bottom = 16.dp, end = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                        tonalElevation = 2.dp
                    ) {
                        Text(
                            text = stringResource(R.string.reader_mode_slides),
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
        if (!isLandscape && isSidebarOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                    .clickable { isSidebarOpen = false }
            )
            ThumbnailsSidebar(
                pages = pages,
                currentPage = currentPage,
                onPageClick = { page ->
                    isSidebarOpen = false
                    scope.launch { state.scrollToPage(page) }
                },
                onClose = { isSidebarOpen = false },
                thumbnailLoader = thumbnailLoader,
                outline = outline,
                onOutlineClick = { item ->
                    isSidebarOpen = false
                    onOutlineClick(item)
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(SIDEBAR_WIDTH)
                    .fillMaxHeight()
            )
        }
    }

    externalUri?.let { uri ->
        val openable = isOpenableUri(uri)
        AlertDialog(
            onDismissRequest = { externalUri = null },
            title = { Text(stringResource(R.string.reader_link_title)) },
            text = {
                Text(if (openable) uri else stringResource(R.string.reader_link_unsupported, uri))
            },
            confirmButton = {
                if (openable) {
                    TextButton(onClick = {
                        externalUri = null
                        openExternalUri(context, uri)
                    }) { Text(stringResource(R.string.reader_link_open)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { externalUri = null }) { Text(stringResource(R.string.reader_jump_cancel)) }
            }
        )
    }
}

/** Runs an extraction call; a failure (render process gone, malformed page) just means no data. */
private suspend fun <T> extractOrEmpty(block: suspend () -> List<T>): List<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
    android.util.Log.w("ReaderScreen", "Extraction failed", e)
    emptyList()
}
