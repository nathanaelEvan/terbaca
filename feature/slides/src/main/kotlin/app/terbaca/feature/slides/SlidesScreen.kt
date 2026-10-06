package app.terbaca.feature.slides

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.ui.theme.TerbacaDarkAccent
import app.terbaca.core.ui.theme.TerbacaDarkBackground
import app.terbaca.core.ui.theme.TerbacaDarkBorder
import app.terbaca.core.ui.theme.TerbacaDarkSurface
import app.terbaca.core.ui.theme.TerbacaDarkTextPrimary
import app.terbaca.core.ui.theme.TerbacaDarkTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Slide mode viewer (F-MODE-3, F-MODE-5, F-DECK-4):
 * - Horizontal paging, one slide per screen.
 * - Snap with slight overshoot via [SLIDE_SNAP_DAMPING_RATIO].
 * - Haptic tick when settling on a new slide.
 * - Adjacent slides pre-rendered at full screen resolution via [SLIDE_BEYOND_VIEWPORT_PAGES].
 */
@Composable
fun SlidesScreen(
    pages: List<PageInfo>,
    modifier: Modifier = Modifier,
    renderer: PageRenderer? = null,
    docHandle: Long = 0L,
    initialPage: Int = 0,
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                handleSlideKeyEvent(event, pagerState, pages.size, coroutineScope, onSearch = onSearchRequested)
            }
            .background(TerbacaDarkBackground)
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = SLIDE_BEYOND_VIEWPORT_PAGES,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            SlideItem(
                page = pages[pageIndex],
                renderer = renderer,
                docHandle = docHandle,
                slideCache = slideCache
            )
        }

        SlideOverlay(
            currentPage = pagerState.currentPage,
            pageCount = pages.size,
            onModeToggle = onModeToggle,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun SlideItem(
    page: PageInfo,
    renderer: PageRenderer?,
    docHandle: Long,
    slideCache: SlideCache<SlideKey, Bitmap>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(TerbacaDarkBackground),
        contentAlignment = Alignment.Center
    ) {
        val (targetW, targetH) = calculateAspectFit(
            viewportWidth = constraints.maxWidth,
            viewportHeight = constraints.maxHeight,
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
                val rendered = withContext(Dispatchers.IO) {
                    try {
                        renderer.renderTile(
                            docHandle = docHandle,
                            pageIndex = page.index,
                            fullWidth = targetW,
                            fullHeight = targetH,
                            startX = 0,
                            startY = 0,
                            tileWidth = targetW,
                            tileHeight = targetH
                        )
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (rendered != null) {
                    slideCache.put(cacheKey, rendered)
                    bitmap = rendered
                }
            }
        }

        val currentBitmap = bitmap
        if (currentBitmap != null && !currentBitmap.isRecycled) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.slides_slide_description, page.index + 1),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
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

@Composable
private fun SlideOverlay(
    currentPage: Int,
    pageCount: Int,
    onModeToggle: (() -> Unit)?,
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
