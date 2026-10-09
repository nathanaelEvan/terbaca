package app.terbaca.feature.slides

import android.graphics.Bitmap
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** Pages of a 2-up pager over [slideCount] slides: one per pair, the last one possibly alone. */
fun twoUpPageCount(slideCount: Int): Int = (slideCount + 1) / 2

/** Slides shown on 2-up page [pair] (F-MODE-4). */
fun twoUpSlides(pair: Int, slideCount: Int): IntRange = (pair * 2)..minOf(pair * 2 + 1, slideCount - 1)

/**
 * Slide 2-up stacked (F-MODE-4): in portrait, two consecutive slides one above the other, paging
 * by two. Opens at the pair holding [currentSlide] and reports the first slide of each pair it
 * settles on through [onSlideChange], so the single-slide pager, sidebar and saved position follow.
 * Pinch-zoom is off here; switch back to one slide per screen to zoom.
 */
@Composable
internal fun TwoUpSlides(
    pages: List<PageInfo>,
    currentSlide: Int,
    renderer: PageRenderer?,
    docHandle: Long,
    slideCache: SlideCache<SlideKey, Bitmap>,
    coroutineScope: CoroutineScope,
    decay: DecayAnimationSpec<Float>,
    onSlideChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pairState = rememberPagerState(initialPage = currentSlide / 2, pageCount = { twoUpPageCount(pages.size) })
    val currentOnSlideChange by rememberUpdatedState(onSlideChange)
    LaunchedEffect(pairState) {
        snapshotFlow { pairState.settledPage }.drop(1).distinctUntilChanged().collect { pair ->
            currentOnSlideChange(pair * 2)
        }
    }
    // Inactive slides ignore zoom gestures; one zoom state serves them all.
    val zoomState = remember { SlideZoomState() }
    HorizontalPager(state = pairState, modifier = modifier.fillMaxSize()) { pair ->
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (index in twoUpSlides(pair, pages.size)) {
                SlideItem(
                    page = pages[index],
                    renderer = renderer,
                    docHandle = docHandle,
                    slideCache = slideCache,
                    zoomState = zoomState,
                    isActive = { false },
                    coroutineScope = coroutineScope,
                    decay = decay,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
    }
}
