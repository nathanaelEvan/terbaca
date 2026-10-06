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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.terbaca.core.data.RecentDocument
import app.terbaca.core.data.computeContentHash
import app.terbaca.core.model.PageInfo
import app.terbaca.core.model.ReadingMode
import app.terbaca.core.model.autoDetectReadingMode
import app.terbaca.core.pdfium.PdfiumRemotePageRenderer
import app.terbaca.core.render.cache.ThumbnailDiskCache
import app.terbaca.core.render.cache.ThumbnailPipeline
import app.terbaca.core.render.cache.TileCache
import app.terbaca.core.ui.theme.TerbacaTheme
import app.terbaca.feature.library.LibraryScreen
import app.terbaca.feature.reader.ReaderScreen
import app.terbaca.feature.slides.SlidesScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private class OpenedDocument(
        val handle: Long,
        val pages: List<PageInfo>,
        val initialPage: Int = 0,
        val savedMode: ReadingMode? = null
    )

    // Bound on first open, so a cold start into the library doesn't spin up the :render process.
    private var renderer: PdfiumRemotePageRenderer? = null
    private val recents by lazy { (application as TerbacaApp).recentsRepository }
    private val thumbnailDiskCache by lazy { (application as TerbacaApp).thumbnailDiskCache }
    private val thumbnailPipeline by lazy {
        ThumbnailPipeline(
            memoryCache = TileCache(),
            diskCache = thumbnailDiskCache,
            renderer = renderer()
        )
    }

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
                var openUriString by rememberSaveable { mutableStateOf<String?>(null) }
                var userModeOverride by remember { mutableStateOf<ReadingMode?>(null) }
                var loading by remember { mutableStateOf(corpusAsset != null || openUriString != null) }
                val scope = rememberCoroutineScope()

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        recents.persistReadPermission(uri)
                        openUriString = uri.toString()
                    }
                }

                LaunchedEffect(corpusAsset) {
                    if (corpusAsset != null) {
                        loading = true
                        opened = openCorpusAsset(corpusAsset)
                        loading = false
                    }
                }

                LaunchedEffect(openUriString) {
                    val uriStr = openUriString ?: return@LaunchedEffect
                    if (corpusAsset != null) return@LaunchedEffect
                    if (opened != null) {
                        val oldDoc = opened
                        opened = null
                        withContext(NonCancellable) {
                            oldDoc?.let { renderer().closeDocument(it.handle) }
                        }
                    }
                    loading = true
                    try {
                        Log.d(TAG, "Opening document: $uriStr")
                        val result = openFromUri(Uri.parse(uriStr))
                        if (openUriString == uriStr) {
                            opened = result
                            if (result == null) {
                                openUriString = null
                            }
                            loading = false
                        } else {
                            Log.d(TAG, "Discarding opened document for $uriStr because target changed to $openUriString")
                            result?.let { doc ->
                                withContext(NonCancellable) {
                                    renderer().closeDocument(doc.handle)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Open cancelled for $uriStr")
                        throw e
                    }
                }

                BackHandler(enabled = opened != null || openUriString != null || loading) {
                    opened?.let { doc ->
                        lifecycleScope.launch {
                            withContext(NonCancellable) {
                                renderer?.closeDocument(doc.handle)
                            }
                        }
                    }
                    opened = null
                    openUriString = null
                    userModeOverride = null
                    loading = false
                }

                val doc = opened
                when {
                    doc != null -> {
                        val currentMode = userModeOverride ?: doc.savedMode ?: remember(doc.pages) {
                            autoDetectReadingMode(doc.pages)
                        }
                        if (currentMode == ReadingMode.SLIDE) {
                            SlidesScreen(
                                modifier = Modifier.fillMaxSize(),
                                pages = doc.pages,
                                renderer = renderer(),
                                docHandle = doc.handle,
                                initialPage = doc.initialPage,
                                onPageSettled = { page ->
                                    scope.launch {
                                        openUriString?.let { uriStr ->
                                            recents.updateLastPage(Uri.parse(uriStr), page)
                                        }
                                    }
                                },
                                onModeToggle = {
                                    val newMode = ReadingMode.CONTINUOUS_VERTICAL
                                    userModeOverride = newMode
                                    scope.launch {
                                        openUriString?.let { uriStr ->
                                            recents.updateReadingMode(Uri.parse(uriStr), newMode.name)
                                        }
                                    }
                                }
                            )
                        } else {
                            ReaderScreen(
                                modifier = Modifier.fillMaxSize(),
                                pages = doc.pages,
                                renderer = renderer(),
                                docHandle = doc.handle,
                                initialPage = doc.initialPage,
                                onPageChange = { page ->
                                    scope.launch {
                                        openUriString?.let { uriStr ->
                                            recents.updateLastPage(Uri.parse(uriStr), page)
                                        }
                                    }
                                },
                                onModeToggle = {
                                    val newMode = ReadingMode.SLIDE
                                    userModeOverride = newMode
                                    scope.launch {
                                        openUriString?.let { uriStr ->
                                            recents.updateReadingMode(Uri.parse(uriStr), newMode.name)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    else -> {
                        val entries by recents.recents.collectAsState(initial = emptyList())
                        LibraryScreen(
                            recents = entries,
                            onOpenDocument = { picker.launch(arrayOf(PDF_MIME_TYPE)) },
                            onRecentClick = { recent: RecentDocument ->
                                openUriString = recent.uri
                            },
                            onRemove = { recent -> scope.launch { recents.remove(recent) } },
                            thumbnailLoader = { hash ->
                                withContext(Dispatchers.IO) {
                                    thumbnailDiskCache.get(hash, 0, ThumbnailDiskCache.DEFAULT_THUMBNAIL_WIDTH_PX)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /** Opens a SAF/content URI, records it in recents, and returns null (with a message) on failure. */
    private suspend fun openFromUri(uri: Uri): OpenedDocument? = withContext(Dispatchers.IO) {
        var handle: Long? = null
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: error("Provider returned no file descriptor for $uri")
            // Positional reads: hashing doesn't move the offset PDFium will read from.
            val hash = try {
                computeContentHash(pfd)
            } catch (t: Throwable) {
                pfd.close()
                throw t
            }
            val opened = openPfd(pfd) // takes ownership of pfd
            handle = opened.handle
            val record = recents.recordOpened(uri, recents.displayName(uri), hash, opened.pages.size)
            generateThumbnailIfMissing(opened.handle, hash, opened.pages.firstOrNull())
            val savedMode = record.readingMode?.let {
                runCatching { ReadingMode.valueOf(it) }.getOrNull()
            }
            OpenedDocument(opened.handle, opened.pages, record.lastPage, savedMode)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                handle?.let { renderer().closeDocument(it) }
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                handle?.let { runCatching { renderer().closeDocument(it) } }
            }
            // M2-02 adds dedicated error states (encrypted, corrupt, unavailable).
            Log.w(TAG, "Failed to open $uri", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, R.string.open_failed, Toast.LENGTH_LONG).show()
            }
            null
        }
    }

    private suspend fun openCorpusAsset(asset: String): OpenedDocument? = withContext(Dispatchers.IO) {
        var handle: Long? = null
        try {
            val tempFile = File(cacheDir, "corpus_current.pdf")
            assets.open(asset).use { input -> FileOutputStream(tempFile).use { input.copyTo(it) } }
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val opened = openPfd(pfd)
            handle = opened.handle
            opened
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                handle?.let { renderer().closeDocument(it) }
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                handle?.let { runCatching { renderer().closeDocument(it) } }
            }
            Log.w(TAG, "Failed to open corpus asset $asset", e)
            null
        }
    }

    private suspend fun openPfd(pfd: ParcelFileDescriptor): OpenedDocument {
        val r = renderer()
        openDocumentCallCount++
        Log.d(TAG, "openPfd: opening document (callCount=$openDocumentCallCount)")
        val handle = r.openDocument(pfd)
        try {
            val pages = r.getAllPages(handle)
            require(pages.isNotEmpty()) { "Document has no pages" }
            return OpenedDocument(handle, pages)
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                r.closeDocument(handle)
            }
            throw t
        }
    }

    private fun generateThumbnailIfMissing(docHandle: Long, contentHash: String, firstPage: PageInfo?) {
        if (firstPage == null) return
        if (thumbnailDiskCache.contains(contentHash, firstPage.index, ThumbnailDiskCache.DEFAULT_THUMBNAIL_WIDTH_PX)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                thumbnailPipeline.getThumbnail(docHandle, contentHash, firstPage)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to generate thumbnail for $contentHash", e)
            }
        }
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

        @get:androidx.annotation.VisibleForTesting
        @set:androidx.annotation.VisibleForTesting
        internal var openDocumentCallCount = 0
    }
}
