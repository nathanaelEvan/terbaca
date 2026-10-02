package app.terbaca.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer

@Composable
fun ReaderScreen(
    modifier: Modifier = Modifier,
    pageInfo: PageInfo = PageInfo(0, 612f, 792f),
    renderer: PageRenderer? = null,
    docHandle: Long = 0L
) {
    val state = rememberDocumentTransformState(pageInfo)
    Box(modifier = modifier.fillMaxSize()) {
        DocumentSurface(
            state = state,
            renderer = renderer,
            docHandle = docHandle
        )
    }
}
