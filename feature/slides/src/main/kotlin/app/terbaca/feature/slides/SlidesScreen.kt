package app.terbaca.feature.slides

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.cache.ThumbnailPipeline
import app.terbaca.core.ui.sidebar.ThumbnailsSidebar
import app.terbaca.core.ui.theme.TerbacaDarkAccent
import app.terbaca.core.ui.theme.TerbacaDarkBackground
import app.terbaca.core.ui.theme.TerbacaDarkBorder
import app.terbaca.core.ui.theme.TerbacaDarkSurface
import app.terbaca.core.ui.theme.TerbacaDarkTextPrimary
import app.terbaca.core.ui.theme.TerbacaDarkTextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val SIDEBAR_WIDTH = 240.dp
private const val SCRIM_ALPHA = 0.5f

/**
 * Slide mode viewer (F-MODE-3, F-MODE-5, F-DECK-4):
 * - Horizontal paging, one slide per screen.
 * - Snap with slight overshoot via [SLIDE_SNAP_DAMPING_RATIO].
 * - Haptic tick when settling on a new slide.
 * - Adjacent slides pre-rendered at full screen resolution via [SLIDE_BEYOND_VIEWPORT_PAGES].
 * - Pinch-zoom from fit to 800% with pan and fling; swiping pauses while zoomed (F-NAV-1).
 * - Thumbnails sidebar: docked in landscape, overlay in portrait (F-NAV-4).
 */
@Composable
fun SlidesScreen(
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
    onPageSettled: ((Int) -> Unit)? = null,
    onHapticFeedback: (() -> Unit)? = null,
    onModeToggle: (() -> Unit)? = null,
    onSearchRequested: (() -> Unit)? = null
) {
    if (pages.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(TerbacaDarkBackground)
        )
        return
    }

    val safeInitialPage = initialPage.coerceIn(0, pages.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitialPage, pageCount = { pages.size })
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var isSidebarOpen by rememberSaveable { mutableStateOf(false) }
    // Presentation mode (F-DECK-2/3): no chrome, tap zones, clicker and volume keys, screen on.
    var isPresenting by rememberSaveable { mutableStateOf(false) }
    // Grid overview (F-DECK-1).
    var isGrid by rememberSaveable { mutableStateOf(false) }
    // Slide 2-up stacked (F-MODE-4): portrait only; presenting always shows one slide.
    var twoUpWanted by rememberSaveable { mutableStateOf(false) }
    val twoUp = !isLandscape && twoUpWanted && !isPresenting

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val snapAnimationSpec = remember {
        spring<Float>(
            dampingRatio = SLIDE_SNAP_DAMPING_RATIO,
            stiffness = Spring.StiffnessMediumLow
        )
    }
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = snapAnimationSpec
    )

    val view = LocalView.current
    val currentOnPageSettled by rememberUpdatedState(onPageSettled)
    val settleDetector = remember {
        SlideSettleDetector(
            initialPage = safeInitialPage,
            onPageSettled = { page -> currentOnPageSettled?.invoke(page) },
            onHapticTick = {
                onHapticFeedback?.invoke() ?: view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        )
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { Pair(pagerState.isScrollInProgress, pagerState.settledPage) }
            .collect { (scrolling, settledPage) ->
                settleDetector.onScrollStateChanged(scrolling, settledPage)
            }
    }

    val slideCache = remember(docHandle) { createSlideBitmapCache() }
    DisposableEffect(docHandle) {
        onDispose { slideCache.clear() }
    }

    // One zoom for the current slide; a different slide always starts at fit.
    val zoomState = remember { SlideZoomState() }
    LaunchedEffect(pagerState, zoomState) {
        snapshotFlow { pagerState.currentPage }.drop(1).collect { zoomState.reset() }
    }
    // Flips only when the slide leaves or returns to fit, not per gesture frame.
    val isZoomed by remember(zoomState) { derivedStateOf { zoomState.isZoomed } }
    val density = LocalDensity.current
    val decay = remember(density) { splineBasedDecay<Float>(density) }

    if (isPresenting) {
        PresentationWindowEffect()
        BackHandler { isPresenting = false }
    } else if (isGrid) {
        BackHandler { isGrid = false }
    } else if (isSidebarOpen && !isLandscape) {
        BackHandler { isSidebarOpen = false }
    }
    // Only changes when the slide does.
    val currentPage by remember(pagerState) { derivedStateOf { pagerState.currentPage } }
    val jumpToPage: (Int) -> Unit = { index ->
        sidebarJumpTarget(index, pagerState.targetPage, pages.size)?.let { page ->
            zoomState.reset()
            coroutineScope.launch { pagerState.animateScrollToPage(page) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                handleSlideKeyEvent(
                    event,
                    pagerState,
                    pages.size,
                    coroutineScope,
                    onSearch = onSearchRequested,
                    onNavigate = zoomState::reset,
                    presenting = isPresenting,
                    onExitPresentation = { isPresenting = false }
                )
            }
            .background(TerbacaDarkBackground)
    ) {
        // One layout for both orientations, so the pager keeps its composition position (and its
        // rendered slides) on rotation: docked sidebar in this Row, portrait overlay below.
        Row(modifier = Modifier.fillMaxSize()) {
            if (isLandscape && isSidebarOpen && !isPresenting) {
                ThumbnailsSidebar(
                    pages = pages,
                    currentPage = currentPage,
                    onPageClick = jumpToPage,
                    onClose = { isSidebarOpen = false },
                    thumbnailLoader = thumbnailLoader,
                    modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight()
                )
            }
            // Presentation tap zones (F-DECK-2) on the pager's parent: the pager consumes swipes, so
            // only taps reach this handler.
            val tapZones = if (isPresenting && !isZoomed) {
                Modifier.pointerInput(pagerState, pages.size) {
                    detectTapGestures { position ->
                        val action = presentationTapAction(position.x, size.width.toFloat())
                        slideNavTargetPage(action, pagerState.targetPage, pages.size)?.let { page ->
                            coroutineScope.launch { pagerState.animateScrollToPage(page) }
                        }
                    }
                }
            } else {
                Modifier
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight().then(tapZones)) {
                // The grid and the pager share each slide's image, so opening the grid shrinks the
                // current slide into its cell, and picking a cell grows it back into the pager.
                if (twoUp) {
                    TwoUpSlides(
                        pages = pages,
                        currentSlide = currentPage,
                        renderer = renderer,
                        docHandle = docHandle,
                        slideCache = slideCache,
                        coroutineScope = coroutineScope,
                        decay = decay,
                        onSlideChange = { slide -> pagerState.requestScrollToPage(slide) }
                    )
                } else SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(targetState = isGrid, label = "slideGrid") { showGrid ->
                        if (showGrid) {
                            SlideGrid(
                                pages = pages,
                                currentPage = currentPage,
                                thumbnailLoader = thumbnailLoader,
                                onSlideClick = { index ->
                                    zoomState.reset()
                                    coroutineScope.launch {
                                        pagerState.scrollToPage(index)
                                        isGrid = false
                                    }
                                },
                                cellModifier = { index ->
                                    Modifier.sharedElement(
                                        rememberSharedContentState(key = slideSharedKey(index)),
                                        animatedVisibilityScope = this@AnimatedContent
                                    )
                                }
                            )
                        } else {
                            HorizontalPager(
                                state = pagerState,
                                beyondViewportPageCount = SLIDE_BEYOND_VIEWPORT_PAGES,
                                flingBehavior = flingBehavior,
                                // Zoomed in, a drag pans the slide instead of paging (F-NAV-1).
                                userScrollEnabled = !isZoomed,
                                modifier = Modifier
                                    .fillMaxSize()
                                    // A resize or rotation refits the slide.
                                    .onSizeChanged { zoomState.reset() }
                            ) { pageIndex ->
                                SlideItem(
                                    page = pages[pageIndex],
                                    renderer = renderer,
                                    docHandle = docHandle,
                                    slideCache = slideCache,
                                    zoomState = zoomState,
                                    isActive = { pagerState.currentPage == pageIndex },
                                    coroutineScope = coroutineScope,
                                    decay = decay,
                                    imageModifier = if (pageIndex == currentPage) {
                                        Modifier.sharedElement(
                                            rememberSharedContentState(key = slideSharedKey(pageIndex)),
                                            animatedVisibilityScope = this@AnimatedContent
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                            }
                        }
                    }
                }

                if (!isSidebarOpen && !isPresenting && !isGrid) {
                    SidebarToggleChip(
                        onClick = { isSidebarOpen = true },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .safeDrawingPadding()
                    )
                }

                if (!isPresenting && !isGrid) {
                    SlideOverlay(
                        currentPage = currentPage,
                        pageCount = pages.size,
                        onModeToggle = onModeToggle,
                        onPresent = {
                            isSidebarOpen = false
                            isPresenting = true
                        },
                        onGrid = {
                            zoomState.reset()
                            twoUpWanted = false
                            isGrid = true
                        },
                        twoUp = twoUp,
                        onTwoUp = if (isLandscape) null else ({ twoUpWanted = !twoUpWanted }),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
        if (!isLandscape && isSidebarOpen && !isPresenting) {
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
                    jumpToPage(page)
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

@Composable
internal fun SlideItem(
    page: PageInfo,
    renderer: PageRenderer?,
    docHandle: Long,
    slideCache: SlideCache<SlideKey, Bitmap>,
    zoomState: SlideZoomState,
    isActive: () -> Boolean,
    coroutineScope: CoroutineScope,
    decay: DecayAnimationSpec<Float>,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(TerbacaDarkBackground),
        contentAlignment = Alignment.Center
    ) {
        val viewportW = constraints.maxWidth
        val viewportH = constraints.maxHeight
        val (targetW, targetH) = calculateAspectFit(
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            pageWidthPoints = page.widthPoints,
            pageHeightPoints = page.heightPoints
        )
        val cacheKey = SlideKey(page.index, targetW, targetH)
        // Not keyed by size: after a resize the old bitmap stays up until the sharp one replaces it.
        var bitmap by remember(page.index, docHandle) {
            mutableStateOf(slideCache.get(cacheKey))
        }

        LaunchedEffect(cacheKey, docHandle, renderer) {
            val cached = slideCache.get(cacheKey)
            if (cached != null) {
                bitmap = cached
                return@LaunchedEffect
            }

            if (renderer != null && docHandle > 0L && targetW > 0 && targetH > 0) {
                val rendered = renderSlideRegion(
                    renderer,
                    docHandle,
                    page.index,
                    SlideDetailRegion(targetW, targetH, 0, 0, targetW, targetH)
                )
                if (rendered != null) {
                    slideCache.put(cacheKey, rendered)
                    bitmap = rendered
                }
            }
        }

        val detail = rememberSlideDetail(
            zoomState = zoomState,
            isActive = isActive,
            renderer = renderer,
            docHandle = docHandle,
            pageIndex = page.index,
            viewport = IntSize(viewportW, viewportH),
            content = IntSize(targetW, targetH)
        )

        // The gesture handler outlives recompositions; read the size through state so it follows resizes.
        val contentSize = rememberUpdatedState(targetW to targetH)
        val slideModifier = Modifier
            .fillMaxSize()
            .slideZoomGestures(
                zoomState = zoomState,
                isActive = isActive,
                contentSize = { contentSize.value },
                coroutineScope = coroutineScope,
                decay = decay
            )
            // Transform read in the layer lambda only: pinch and pan never recompose (PRD §4.6).
            .graphicsLayer {
                if (isActive()) {
                    scaleX = zoomState.scale
                    scaleY = zoomState.scale
                    translationX = zoomState.offsetX
                    translationY = zoomState.offsetY
                }
            }
            .drawWithContent {
                drawContent()
                val sharp = detail.value
                if (sharp != null && isActive() && zoomState.isZoomed) {
                    drawSlideDetail(sharp, targetW, targetH)
                }
            }

        val currentBitmap = bitmap
        if (currentBitmap != null && !currentBitmap.isRecycled) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.slides_slide_description, page.index + 1),
                contentScale = ContentScale.Fit,
                modifier = imageModifier.then(slideModifier)
            )
        } else {
            val density = LocalDensity.current.density
            val widthDp = if (density > 0f) (targetW / density).dp else 200.dp
            val heightDp = if (density > 0f) (targetH / density).dp else 150.dp
            Box(
                modifier = Modifier
                    .size(widthDp, heightDp)
                    .background(Color.White)
            )
        }
    }
}

/** A sharp render of [region], drawn over the upscaled fitted slide while zoomed. */
private class SlideDetail(val region: SlideDetailRegion, val image: ImageBitmap)

/** What the sharp layer should do for the current zoom. */
private sealed interface DetailTarget {
    /** Not zoomed or not the current slide: drop the sharp layer. */
    data object None : DetailTarget

    /** Mid-gesture: keep the last sharp layer (it moves with the slide) until the gesture settles. */
    data object Keep : DetailTarget

    data class Render(val region: SlideDetailRegion) : DetailTarget
}

/**
 * Once a zoom gesture settles, renders the visible part of the slide at the zoomed resolution, so
 * it is 1:1 on screen instead of the upscaled fitted bitmap (F-NAV-1). One render per settle,
 * cancelled if the slide is touched again first.
 */
@Composable
private fun rememberSlideDetail(
    zoomState: SlideZoomState,
    isActive: () -> Boolean,
    renderer: PageRenderer?,
    docHandle: Long,
    pageIndex: Int,
    viewport: IntSize,
    content: IntSize
): State<SlideDetail?> {
    val detail = remember(pageIndex, docHandle) { mutableStateOf<SlideDetail?>(null) }
    LaunchedEffect(zoomState, renderer, docHandle, pageIndex, viewport, content) {
        if (renderer == null || docHandle <= 0L) return@LaunchedEffect
        snapshotFlow {
            when {
                !isActive() || !zoomState.isZoomed -> DetailTarget.None
                zoomState.isInteracting -> DetailTarget.Keep
                else -> visibleDetailRegion(
                    viewportWidth = viewport.width,
                    viewportHeight = viewport.height,
                    contentWidth = content.width,
                    contentHeight = content.height,
                    scale = zoomState.scale,
                    offsetX = zoomState.offsetX,
                    offsetY = zoomState.offsetY
                )?.let { DetailTarget.Render(it) } ?: DetailTarget.None
            }
        }.distinctUntilChanged().collectLatest { target ->
            when (target) {
                DetailTarget.None -> detail.value = null
                DetailTarget.Keep -> Unit
                is DetailTarget.Render -> if (detail.value?.region != target.region) {
                    renderSlideRegion(renderer, docHandle, pageIndex, target.region)?.let {
                        detail.value = SlideDetail(target.region, it.asImageBitmap())
                    }
                }
            }
        }
    }
    return detail
}

/** Draws [detail] where its region sits on the fitted slide, in the slide's untransformed space. */
private fun DrawScope.drawSlideDetail(detail: SlideDetail, contentW: Int, contentH: Int) {
    val region = detail.region
    // The fitted slide is centred; the region was rendered at fullWidth / contentW times its size.
    val renderScale = region.fullWidth.toFloat() / contentW
    val originX = (size.width - contentW) / 2f
    val originY = (size.height - contentH) / 2f
    drawImage(
        image = detail.image,
        dstOffset = IntOffset(
            (originX + region.left / renderScale).roundToInt(),
            (originY + region.top / renderScale).roundToInt()
        ),
        dstSize = IntSize(
            (region.width / renderScale).roundToInt(),
            (region.height / renderScale).roundToInt()
        )
    )
}

private suspend fun renderSlideRegion(
    renderer: PageRenderer,
    docHandle: Long,
    pageIndex: Int,
    region: SlideDetailRegion
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        renderer.renderTile(
            docHandle = docHandle,
            pageIndex = pageIndex,
            fullWidth = region.fullWidth,
            fullHeight = region.fullHeight,
            startX = region.left,
            startY = region.top,
            tileWidth = region.width,
            tileHeight = region.height
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}

@Composable
private fun SidebarToggleChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(top = 16.dp, start = 16.dp),
        color = TerbacaDarkSurface.copy(alpha = 0.90f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, TerbacaDarkBorder)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp)
        ) {
            Text(
                text = stringResource(R.string.slides_sidebar_toggle),
                color = TerbacaDarkAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SlideOverlay(
    currentPage: Int,
    pageCount: Int,
    onModeToggle: (() -> Unit)?,
    onPresent: () -> Unit,
    onGrid: () -> Unit,
    twoUp: Boolean,
    onTwoUp: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(bottom = 24.dp),
        color = TerbacaDarkSurface.copy(alpha = 0.90f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, TerbacaDarkBorder)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 32.dp)
                // The ≥48dp mode chip carries its own end padding.
                .padding(start = 14.dp, end = if (onModeToggle != null) 6.dp else 14.dp)
        ) {
            Text(
                text = "${currentPage + 1} / $pageCount",
                color = TerbacaDarkTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "•",
                color = TerbacaDarkTextSecondary,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = onPresent)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.slides_present),
                    color = TerbacaDarkAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = onGrid)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.slides_grid),
                    color = TerbacaDarkAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (onTwoUp != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button, onClick = onTwoUp)
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = stringResource(if (twoUp) R.string.slides_one_up else R.string.slides_two_up),
                        color = TerbacaDarkAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (onModeToggle != null) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "•",
                    color = TerbacaDarkTextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(2.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button, onClick = onModeToggle)
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.slides_mode_scroll),
                        color = TerbacaDarkAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** Shared-element key of slide [index] between the pager and the grid (F-DECK-1). */
private fun slideSharedKey(index: Int) = "slide-$index"
