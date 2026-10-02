package app.terbaca

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.terbaca.core.data.RecentDocument
import app.terbaca.core.data.computeContentHash
import app.terbaca.core.model.PageInfo
import app.terbaca.core.pdfium.PdfiumRemotePageRenderer
import app.terbaca.core.ui.theme.TerbacaTheme
import app.terbaca.feature.library.LibraryScreen
import app.terbaca.feature.reader.ReaderScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private class OpenedDocument(val handle: Long, val pageInfo: PageInfo)

    // Bound on first open, so a cold start into the library doesn't spin up the :render process.
    private var renderer: PdfiumRemotePageRenderer? = null
    private val recents by lazy { (application as TerbacaApp).recentsRepository }

    private fun renderer(): PdfiumRemotePageRenderer =
        renderer ?: PdfiumRemotePageRenderer(applicationContext).also { renderer = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighestRefreshRate()

        // Benchmarks and baseline-profile generation open a bundled corpus file directly.
        val corpusAsset = intent?.getStringExtra(EXTRA_CORPUS_FILE)

        setContent {
            TerbacaTheme {
                var opened by remember { mutableStateOf<OpenedDocument?>(null) }
                var loading by remember { mutableStateOf(corpusAsset != null) }
                val scope = rememberCoroutineScope()

                fun open(load: suspend () -> OpenedDocument?) {
                    loading = true
                    scope.launch {
                        opened = load()
                        loading = false
                    }
                }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) open { openFromUri(uri, persist = true) }
                }

                LaunchedEffect(corpusAsset) {
                    if (corpusAsset != null) open { openCorpusAsset(corpusAsset) }
                }

                BackHandler(enabled = opened != null) {
                    opened?.let { doc -> lifecycleScope.launch { renderer?.closeDocument(doc.handle) } }
                    opened = null
                }

                val doc = opened
                when {
                    doc != null -> ReaderScreen(
                        modifier = Modifier.fillMaxSize(),
                        pageInfo = doc.pageInfo,
                        renderer = renderer,
                        docHandle = doc.handle
                    )
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    else -> {
                        val entries by recents.recents.collectAsState(initial = emptyList())
                        LibraryScreen(
                            recents = entries,
                            onOpenDocument = { picker.launch(arrayOf(PDF_MIME_TYPE)) },
                            onRecentClick = { recent: RecentDocument ->
                                open { openFromUri(Uri.parse(recent.uri), persist = false) }
                            },
                            onRemove = { recent -> scope.launch { recents.remove(recent) } }
                        )
                    }
                }
            }
        }
    }

    /** Opens a SAF/content URI, records it in recents, and returns null (with a message) on failure. */
    private suspend fun openFromUri(uri: Uri, persist: Boolean): OpenedDocument? = withContext(Dispatchers.IO) {
        if (persist) recents.persistReadPermission(uri)
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: error("Provider returned no file descriptor for $uri")
            // Positional reads: hashing doesn't move the offset PDFium will read from.
            val hash = try {
                computeContentHash(pfd)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
            val opened = openPfd(pfd) // takes ownership of pfd
            val pageCount = renderer().getPageCount(opened.handle)
            recents.recordOpened(uri, recents.displayName(uri), hash, pageCount)
            opened
        } catch (e: Exception) {
            // M2-02 adds dedicated error states (encrypted, corrupt, unavailable).
            Log.w(TAG, "Failed to open $uri", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, R.string.open_failed, Toast.LENGTH_LONG).show()
            }
            null
        }
    }

    private suspend fun openCorpusAsset(asset: String): OpenedDocument? = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = File(cacheDir, "corpus_current.pdf")
            assets.open(asset).use { input -> FileOutputStream(tempFile).use { input.copyTo(it) } }
            openPfd(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY))
        }.onFailure { Log.w(TAG, "Failed to open corpus asset $asset", it) }.getOrNull()
    }

    private suspend fun openPfd(pfd: ParcelFileDescriptor): OpenedDocument {
        val r = renderer()
        val handle = r.openDocument(pfd)
        return OpenedDocument(handle, r.getPageInfo(handle, 0))
    }

    /**
     * PRD §4.6: ask for the display's highest refresh rate (144 Hz on Pad 7) at the current
     * resolution. HyperOS caps apps at 120 Hz (ADR 0004); the request stays in case that changes.
     */
    private fun requestHighestRefreshRate() {
        val display = display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        renderer = null
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PDF_MIME_TYPE = "application/pdf"
        const val EXTRA_CORPUS_FILE = "app.terbaca.extra.CORPUS_FILE"
    }
}
