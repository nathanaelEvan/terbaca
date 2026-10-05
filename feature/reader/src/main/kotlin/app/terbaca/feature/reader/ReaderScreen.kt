package app.terbaca.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.terbaca.core.model.PageInfo
import app.terbaca.core.render.PageRenderer

@Composable
fun ReaderScreen(
    pages: List<PageInfo>,
    modifier: Modifier = Modifier,
    renderer: PageRenderer? = null,
    docHandle: Long = 0L,
    onModeToggle: (() -> Unit)? = null
) {
    val layout = remember(pages) { DocumentLayout(pages) }
    val state = rememberDocumentTransformState(layout)
    Box(modifier = modifier.fillMaxSize()) {
        DocumentSurface(
            state = state,
            renderer = renderer,
            docHandle = docHandle
        )
        FastScrollbar(state)
        PageIndicator(state)
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
