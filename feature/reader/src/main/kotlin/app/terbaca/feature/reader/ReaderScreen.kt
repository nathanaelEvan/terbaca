package app.terbaca.feature.reader

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.RuntimeShader
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.terbaca.core.model.OutlineItem
import app.terbaca.core.model.PageColumnLayout
import app.terbaca.core.model.PageInfo
import app.terbaca.core.model.PageLink
import app.terbaca.core.model.PageRect
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.cache.ThumbnailPipeline
import app.terbaca.core.ui.R as UiR
import app.terbaca.core.ui.chrome.FloatingPill
import app.terbaca.core.ui.chrome.SystemBarsEffect
import app.terbaca.core.ui.chrome.ToolbarHeight
import app.terbaca.core.ui.chrome.ToolbarIconButton
import app.terbaca.core.ui.chrome.ToolbarTextSettingsButton
import app.terbaca.core.ui.chrome.TopToolbar
import app.terbaca.core.ui.chrome.onDragStarted
import app.terbaca.core.ui.sidebar.ThumbnailsSidebar
import app.terbaca.core.ui.theme.TerbacaDarkBackground
import app.terbaca.feature.reader.column.ColumnDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private val SIDEBAR_WIDTH = 240.dp

/** The back chip under the toolbar, with its gaps: link destinations land below it. */
private val BACK_CHIP_CLEARANCE = 56.dp
private const val SEARCH_DEBOUNCE_MS = 250L

/** A search hit lands this far below the viewport top (plus the destination margin). */
private const val SEARCH_HIT_LEAD_POINTS = 48f
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
    onSearchRequested: (() -> Unit)? = null,
    title: String = "",
    onBack: (() -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Two-page spread (F-MODE-2): offered in landscape for portrait pages, cover alone by default.
    val canSpread = isLandscape && pages.first().heightPoints > pages.first().widthPoints
    var spreadWanted by rememberSaveable { mutableStateOf(false) }
    var coverAlone by rememberSaveable { mutableStateOf(true) }
    val spread = canSpread && spreadWanted
    val layout = remember(pages, spread, coverAlone) { DocumentLayout(pages, spread = spread, coverAlone = coverAlone) }
    // A layout switch rebuilds the state; it reopens at the page being read, not the initial one.
    val firstLayout = remember { layout }
    var lastPage by rememberSaveable { mutableIntStateOf(initialPage) }
    val state = rememberDocumentTransformState(
        layout,
        if (layout === firstLayout) initialPage else lastPage,
        initialViewport.takeIf { layout === firstLayout }
    )
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
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

    // Page theme (F-THEME-2/3/4/5): a shader over the surface, so switching needs no re-render.
    var pageTheme by remember { mutableStateOf(loadPageTheme(context)) }
    var invertImages by rememberSaveable(docHandle) { mutableStateOf(false) }
    val themeShader = remember { RuntimeShader(PAGE_THEME_AGSL) }
    val imageBounds = remember(renderer, docHandle) { mutableStateMapOf<Int, List<PageRect>>() }
    LaunchedEffect(pageTheme, renderer, docHandle) {
        val r = renderer ?: return@LaunchedEffect
        if (pageTheme == PageTheme.ORIGINAL) return@LaunchedEffect
        // Image bounds for the pages coming into view, once per page.
        snapshotFlow { if (state.hasViewport) state.layout.pagesIn(state.getVisibleRectPoints()) else IntRange.EMPTY }
            .distinctUntilChanged()
            .collect { visible ->
                for (index in visible) {
                    if (index !in imageBounds) imageBounds[index] = extractOrEmpty { r.getImageBounds(docHandle, index) }
                }
            }
    }

    // Text selection (F-ACAD-6): a long-press selects the word under the finger.
    var selection by remember(docHandle) { mutableStateOf<TextSelection?>(null) }

    // Column-aware double-tap zoom (F-ACAD-1): columns are detected once per page, on its first
    // double-tap. The position before a double-tap zoom is kept, so the next double-tap returns.
    val pageColumns = remember(renderer, docHandle) { HashMap<Int, PageColumnLayout>() }
    var beforeDoubleTap by remember(docHandle) { mutableStateOf<ReaderViewport?>(null) }

    // Following a link or outline entry saves the position first, for the back chip. The
    // destination lands below the chip that then appears, not under it.
    // The pages run under the toolbar, so a destination or a search hit lands below it.
    val density = LocalDensity.current
    val toolbarClearancePx = WindowInsets.statusBars.getTop(density) + with(density) { ToolbarHeight.toPx() }
    val backChipClearancePx = toolbarClearancePx + with(density) { BACK_CHIP_CLEARANCE.toPx() }
    val jumpTo: (Int, Float?) -> Unit = { page, pageY ->
        jumpHistory.push(JumpEntry(state.viewport, state.currentPage))
        scope.launch { state.scrollToDestination(page, pageY, backChipClearancePx) }
    }
    val onOutlineClick: (OutlineItem) -> Unit = { item ->
        if (item.pageIndex >= 0) jumpTo(item.pageIndex, item.destY)
    }

    // Search (F-ACAD-5): hits stream in page by page while the search runs; a new query restarts it.
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(docHandle) { mutableStateListOf<SearchHit>() }
    var isSearching by remember { mutableStateOf(false) }
    var activeHit by remember { mutableIntStateOf(-1) }
    val hitsByPage by remember { derivedStateOf { hits.groupBy { it.pageIndex } } }
    val showResults = isSearchOpen && query.trim().length >= MIN_QUERY_LENGTH

    LaunchedEffect(query, isSearchOpen, renderer, docHandle) {
        hits.clear()
        activeHit = -1
        val r = renderer ?: return@LaunchedEffect
        if (!isSearchOpen || query.trim().length < MIN_QUERY_LENGTH) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        isSearching = true
        try {
            searchDocument(pages.size, query) { index ->
                extractOrEmpty { listOf(r.getPageText(docHandle, index)) }.firstOrNull()
            }.collect { hits.addAll(it) }
        } finally {
            isSearching = false
        }
    }

    val goToHit: (Int) -> Unit = { index ->
        hits.getOrNull(index)?.let { hit ->
            activeHit = index
            val y = hit.rects.firstOrNull()?.top ?: 0f
            scope.launch { state.scrollToDestination(hit.pageIndex, y - SEARCH_HIT_LEAD_POINTS, toolbarClearancePx) }
        }
    }
    // The first next/previous starts from the current page rather than the document start.
    val nextHit: () -> Unit = {
        if (hits.isNotEmpty()) {
            goToHit(
                if (activeHit < 0) {
                    hits.indexOfFirst { it.pageIndex >= state.currentPage }.takeIf { it >= 0 } ?: 0
                } else {
                    (activeHit + 1) % hits.size
                }
            )
        }
    }
    val previousHit: () -> Unit = {
        if (hits.isNotEmpty()) {
            goToHit(
                if (activeHit < 0) {
                    hits.indexOfLast { it.pageIndex <= state.currentPage }.takeIf { it >= 0 } ?: hits.lastIndex
                } else {
                    (activeHit - 1 + hits.size) % hits.size
                }
            )
        }
    }
    val closeSearch: () -> Unit = {
        isSearchOpen = false
        query = ""
    }
    val searchResults: (@Composable (Modifier) -> Unit)? = if (showResults) {
        { m ->
            SearchResultsList(
                hits = hits,
                activeHit = activeHit,
                searching = isSearching,
                onClick = { i ->
                    if (!isLandscape) isSidebarOpen = false
                    goToHit(i)
                },
                modifier = m
            )
        }
    } else {
        null
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
                lastPage = page
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

    // Chrome (toolbar, page pill) hides on a tap or a drag, so only the page is left; while the
    // sidebar, search or the settings are open it stays. The system bars go with it.
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    val chromeShown = chromeVisible || isSidebarOpen || isSearchOpen || showSettings
    SystemBarsEffect(hidden = !chromeShown)

    if (isSidebarOpen && !isLandscape) {
        BackHandler { isSidebarOpen = false }
    } else if (isSearchOpen) {
        BackHandler { closeSearch() }
    }

    // Only changes when the page does; reading state.currentPage here recomposed every scroll frame.
    val currentPage by remember(state) { derivedStateOf { state.currentPage } }
    // Overlays under the toolbar (back chip, Copy) sit this far below the status bar.
    val overlayTop = if (chromeShown) ToolbarHeight + 8.dp else 8.dp

    // Edge to edge: the pages run under the system bars, so hiding them never resizes the page.
    // Only the chrome keeps clear of the bars.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TerbacaDarkBackground)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                handleReaderKeyEvent(event, state, onSearch = onSearchRequested ?: { isSearchOpen = true })
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
                    thumbnailLoader = thumbnailLoader,
                    outline = outline,
                    onOutlineClick = onOutlineClick,
                    searchResults = searchResults,
                    modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight()
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onDragStarted { if (!isSidebarOpen && !isSearchOpen) chromeVisible = false }
            ) {
                // A tap that isn't a drag follows the link under it, or shows/hides the chrome.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Read in the layer block only, so scrolling updates the shader's page and
                        // image rectangles without recomposing (§4.6).
                        .graphicsLayer {
                            renderEffect = pageThemeEffect(
                                themeShader,
                                pageTheme,
                                invertImages,
                                themeRects(state, state.layout.pagesIn(state.getVisibleRectPoints()), imageBounds)
                            )
                        }
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
                            }, onLongPress = { position ->
                                val r = renderer ?: return@detectTapGestures
                                val point = state.pagePointAt(position) ?: return@detectTapGestures
                                scope.launch {
                                    val text = extractOrEmpty { listOf(r.getPageText(docHandle, point.pageIndex)) }
                                        .firstOrNull() ?: return@launch
                                    val index = charIndexAt(text.charBoxes, point.x, point.y) ?: return@launch
                                    val word = wordAt(text.text, index)
                                    if (!word.isEmpty()) selection = TextSelection(text, word.first, word.last + 1)
                                }
                            }, onTap = { position ->
                                // A tap with a selection or a peek card open only closes it.
                                if (selection != null || peek != null) {
                                    selection = null
                                    peek = null
                                    return@detectTapGestures
                                }
                                val point = state.pagePointAt(position)
                                val r = renderer
                                if (point == null || r == null) {
                                    chromeVisible = !chromeVisible
                                    return@detectTapGestures
                                }
                                scope.launch {
                                    val links = pageLinks[point.pageIndex]
                                        ?: extractOrEmpty { r.getPageLinks(docHandle, point.pageIndex) }
                                            .also { pageLinks[point.pageIndex] = it }
                                    val link = linkAt(links, point.x, point.y)
                                    when {
                                        link == null -> chromeVisible = !chromeVisible
                                        // Peek first (F-ACAD-3); "Go there" on the card follows the link.
                                        link.isInternal -> if (link.targetPageIndex in pages.indices) {
                                            peek = PeekTarget(link.targetPageIndex, link.targetY)
                                        }
                                        else -> externalUri = link.uri
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
                if (showResults) {
                    SearchHighlights(state, hitsByPage, hits.getOrNull(activeHit))
                    SearchMarkers(state, hits)
                }
                selection?.let { current ->
                    SelectionLayer(state, current) { movingStart, index ->
                        selection = moveHandle(current, movingStart, index)
                    }
                    FloatingPill(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = overlayTop),
                        onClick = {
                            copyToClipboard(context, current.text)
                            selection = null
                        }
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.reader_selection_copy),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                FastScrollbar(state)
                PageIndicator(state, visible = chromeShown)

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
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    )
                }

                if (selection == null) {
                    jumpHistory.latest?.let { entry ->
                        FloatingPill(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = overlayTop),
                            onClick = {
                                jumpHistory.pop()?.let { scope.launch { state.restoreViewport(it.viewport) } }
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.reader_back_to_page, entry.page + 1),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        val closeSidebar = stringResource(UiR.string.reader_sidebar_close)
        if (!isLandscape && isSidebarOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                    // TalkBack users get a "Close sidebar" button here, like the tap outside.
                    .semantics { contentDescription = closeSidebar }
                    .clickable(role = Role.Button) { isSidebarOpen = false }
            )
            ThumbnailsSidebar(
                pages = pages,
                currentPage = currentPage,
                onPageClick = { page ->
                    isSidebarOpen = false
                    scope.launch { state.scrollToPage(page) }
                },
                thumbnailLoader = thumbnailLoader,
                outline = outline,
                onOutlineClick = { item ->
                    isSidebarOpen = false
                    onOutlineClick(item)
                },
                searchResults = searchResults,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(SIDEBAR_WIDTH)
                    .fillMaxHeight()
            )
        }

        TopToolbar(
            visible = chromeShown,
            title = title,
            modifier = Modifier.align(Alignment.TopCenter),
            leading = {
                if (onBack != null) {
                    ToolbarIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBackIos,
                        contentDescription = stringResource(R.string.reader_back),
                        onClick = onBack
                    )
                }
                ToolbarIconButton(
                    icon = Icons.Outlined.ViewSidebar,
                    contentDescription = stringResource(R.string.reader_sidebar),
                    onClick = { isSidebarOpen = !isSidebarOpen },
                    selected = isSidebarOpen,
                    // The icon draws its panel on the right; the sidebar opens on the left.
                    modifier = Modifier.graphicsLayer { scaleX = -1f }
                )
            },
            center = if (isSearchOpen) {
                {
                    SearchToolbarContent(
                        query = query,
                        onQueryChange = { query = it },
                        hitCount = hits.size,
                        activeHit = activeHit,
                        searching = isSearching,
                        onPrevious = previousHit,
                        onNext = nextHit,
                        onClose = closeSearch
                    )
                }
            } else {
                null
            },
            trailing = {
                if (!isSearchOpen) {
                    ToolbarIconButton(
                        icon = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.reader_search_toggle),
                        onClick = { isSearchOpen = true }
                    )
                    ToolbarTextSettingsButton(
                        contentDescription = stringResource(R.string.reader_view_settings),
                        onClick = { showSettings = !showSettings },
                        selected = showSettings
                    )
                }
            }
        )

        if (showSettings) {
            ReaderViewSettings(
                spread = spread,
                canSpread = canSpread,
                onSpreadChange = { spreadWanted = it },
                coverAlone = coverAlone,
                onCoverAloneChange = { coverAlone = it },
                onSlides = onModeToggle?.let { toggle ->
                    {
                        showSettings = false
                        toggle()
                    }
                },
                pageTheme = pageTheme,
                onPageThemeChange = { theme ->
                    pageTheme = theme
                    savePageTheme(context, theme)
                },
                invertImages = invertImages,
                onInvertImagesChange = { invertImages = it },
                onDismiss = { showSettings = false }
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
