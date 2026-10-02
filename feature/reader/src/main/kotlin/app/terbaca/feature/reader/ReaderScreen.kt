package app.terbaca.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer

@Composable
fun ReaderScreen(
    pages: List<PageInfo>,
    modifier: Modifier = Modifier,
    renderer: PageRenderer? = null,
    docHandle: Long = 0L
) {
    val layout = remember(pages) { DocumentLayout(pages) }
    val state = rememberDocumentTransformState(layout)
    Box(modifier = modifier.fillMaxSize()) {
        DocumentSurface(
            state = state,
            renderer = renderer,
            docHandle = docHandle
        )
    }
}
