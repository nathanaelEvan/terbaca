package app.terbaca

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terbaca.core.model.PageInfo
import app.terbaca.core.pdfium.PdfiumRemotePageRenderer
import app.terbaca.core.ui.theme.TerbacaDarkBackground
import app.terbaca.core.ui.theme.TerbacaDarkBorder
import app.terbaca.core.ui.theme.TerbacaDarkSurface
import app.terbaca.core.ui.theme.TerbacaDarkTextPrimary
import app.terbaca.core.ui.theme.TerbacaDarkTextSecondary
import app.terbaca.core.ui.theme.TerbacaTheme
import app.terbaca.feature.reader.ReaderScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private var renderer: PdfiumRemotePageRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val corpusAsset = intent?.getStringExtra(EXTRA_CORPUS_FILE) ?: DEFAULT_CORPUS_ASSET

        setContent {
            TerbacaTheme {
                var pageInfo by remember { mutableStateOf<PageInfo?>(null) }
                var docHandle by remember { mutableLongStateOf(0L) }
                var activeRenderer by remember { mutableStateOf<PdfiumRemotePageRenderer?>(null) }
                var loadFailed by remember { mutableStateOf(false) }

                LaunchedEffect(corpusAsset) {
                    try {
                        val r = PdfiumRemotePageRenderer(applicationContext)
                        renderer = r
                        activeRenderer = r

                        val handle = withContext(Dispatchers.IO) {
                            val tempFile = File(cacheDir, "corpus_current.pdf")
                            assets.open(corpusAsset).use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                            r.openDocument(pfd)
                        }
                        val info = withContext(Dispatchers.IO) {
                            r.getPageInfo(handle, 0)
                        }
                        docHandle = handle
                        pageInfo = info
                    } catch (_: Throwable) {
                        loadFailed = true
                    }
                }

                if (pageInfo != null && activeRenderer != null && docHandle > 0L) {
                    ReaderScreen(
                        modifier = Modifier.fillMaxSize(),
                        pageInfo = pageInfo!!,
                        renderer = activeRenderer,
                        docHandle = docHandle
                    )
                } else if (loadFailed) {
                    PlaceholderScreen()
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        renderer = null
    }

    companion object {
        const val EXTRA_CORPUS_FILE = "app.terbaca.extra.CORPUS_FILE"
        const val DEFAULT_CORPUS_ASSET = "corpus/paper_two_column.pdf"
    }
}

@Composable
fun PlaceholderScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = TerbacaDarkBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = TerbacaDarkTextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.placeholder_tagline),
                fontSize = 16.sp,
                color = TerbacaDarkTextSecondary
            )
            Spacer(modifier = Modifier.height(28.dp))
            Surface(
                color = TerbacaDarkSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, TerbacaDarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.placeholder_status),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.placeholder_ready),
                        fontSize = 12.sp,
                        color = TerbacaDarkTextSecondary
                    )
                }
            }
        }
    }
}
