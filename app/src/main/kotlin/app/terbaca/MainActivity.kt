package app.terbaca

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
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
import java.io.FileNotFoundException
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private class OpenedDocument(val handle: Long, val pages: List<PageInfo>)

    private sealed interface OpenResult {
        data class Success(val doc: OpenedDocument) : OpenResult
        data class Failure(val error: DocumentError) : OpenResult
    }

    private val incomingIntent = mutableStateOf<Intent?>(null)

    internal var openUriString by mutableStateOf<String?>(null)
        private set
    internal var recordInRecents by mutableStateOf(true)
        private set

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

    internal fun openRecent(recent: RecentDocument) {
        recordInRecents = true
        openUriString = recent.uri
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            incomingIntent.value = intent
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_OPEN_URI, openUriString)
        outState.putBoolean(KEY_RECORD_IN_RECENTS, recordInRecents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighestRefreshRate()

        if (savedInstanceState != null) {
            openUriString = savedInstanceState.getString(KEY_OPEN_URI)
            recordInRecents = savedInstanceState.getBoolean(KEY_RECORD_IN_RECENTS, true)
        } else if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            incomingIntent.value = intent
        }

        // Benchmarks and baseline-profile generation open a bundled corpus file directly.
        val corpusAsset = intent?.getStringExtra(EXTRA_CORPUS_FILE)

        setContent {
            TerbacaTheme {
                var opened by remember { mutableStateOf<OpenedDocument?>(null) }
                var documentError by remember { mutableStateOf<DocumentError?>(null) }
                var userModeOverride by remember { mutableStateOf<ReadingMode?>(null) }
                val intentToProcess by incomingIntent
                val initialUri = remember(intentToProcess) { extractPdfUri(intentToProcess) }
                var loading by remember {
                    mutableStateOf(corpusAsset != null || openUriString != null || initialUri != null)
                }
                val scope = rememberCoroutineScope()

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        recordInRecents = recents.persistReadPermission(uri)
                        openUriString = uri.toString()
                    }
                }

                LaunchedEffect(corpusAsset) {
                    if (corpusAsset != null) {
                        loading = true
                        documentError = null
                        when (val result = openCorpusAsset(corpusAsset)) {
                            is OpenResult.Success -> {
                                opened = result.doc
                                documentError = null
                            }
                            is OpenResult.Failure -> {
                                opened = null
                                documentError = result.error
                            }
                        }
                        loading = false
                    }
                }

                LaunchedEffect(intentToProcess) {
                    val currentIntent = intentToProcess ?: return@LaunchedEffect
                    val uri = extractPdfUri(currentIntent)
                    if (uri != null) {
                        if (!isAllowedPdfScheme(uri.scheme)) {
                            opened = null
                            openUriString = null
                            documentError = DocumentError.Unavailable()
                            loading = false
                            return@LaunchedEffect
                        }
                        val persisted = recents.persistReadPermission(uri)
                        recordInRecents = shouldRecordInRecents(currentIntent.action, persisted)
                        openUriString = uri.toString()
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
                    documentError = null
                    try {
                        Log.d(TAG, "Opening document: $uriStr")
                        val result = openFromUri(Uri.parse(uriStr), recordInRecents = recordInRecents)
                        if (openUriString == uriStr) {
                            when (result) {
                                is OpenResult.Success -> {
                                    opened = result.doc
                                    documentError = null
                                }
                                is OpenResult.Failure -> {
                                    opened = null
                                    openUriString = null
                                    documentError = result.error
                                }
                            }
                            loading = false
                        } else {
                            Log.d(TAG, "Discarding opened document for $uriStr because target changed to $openUriString")
                            if (result is OpenResult.Success) {
                                withContext(NonCancellable) {
                                    renderer().closeDocument(result.doc.handle)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Open cancelled for $uriStr")
                        throw e
                    }
                }

                BackHandler(enabled = opened != null || documentError != null || openUriString != null || loading) {
                    opened?.let { doc ->
                        lifecycleScope.launch {
                            withContext(NonCancellable) {
                                renderer?.closeDocument(doc.handle)
                            }
                        }
                    }
                    opened = null
                    documentError = null
                    openUriString = null
                    userModeOverride = null
                    loading = false
                }

                val doc = opened
                val err = documentError
                when {
                    doc != null -> {
                        val currentMode = userModeOverride ?: remember(doc.pages) {
                            autoDetectReadingMode(doc.pages)
                        }
                        if (currentMode == ReadingMode.SLIDE) {
                            SlidesScreen(
                                modifier = Modifier.fillMaxSize(),
                                pages = doc.pages,
                                renderer = renderer(),
                                docHandle = doc.handle,
                                onModeToggle = {
                                    userModeOverride = ReadingMode.CONTINUOUS_VERTICAL
                                }
                            )
                        } else {
                            ReaderScreen(
                                modifier = Modifier.fillMaxSize(),
                                pages = doc.pages,
                                renderer = renderer(),
                                docHandle = doc.handle,
                                onModeToggle = {
                                    userModeOverride = ReadingMode.SLIDE
                                }
                            )
                        }
                    }
                    err != null -> {
                        DocumentErrorScreen(
                            error = err,
                            onDismiss = {
                                documentError = null
                                openUriString = null
                            },
                            onOpenOther = {
                                documentError = null
                                openUriString = null
                                picker.launch(arrayOf(PDF_MIME_TYPE))
                            }
                        )
                    }
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    else -> {
                        val entries by recents.recents.collectAsState(initial = emptyList())
                        LibraryScreen(
                            recents = entries,
                            onOpenDocument = { picker.launch(arrayOf(PDF_MIME_TYPE)) },
                            onRecentClick = { recent -> openRecent(recent) },
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

    /** Opens a SAF/content URI, records it in recents if requested, and returns an OpenResult. */
    private suspend fun openFromUri(uri: Uri, recordInRecents: Boolean = true): OpenResult = withContext(Dispatchers.IO) {
        if (!isAllowedPdfScheme(uri.scheme)) {
            Log.w(TAG, "Rejected non-content URI: $uri")
            return@withContext OpenResult.Failure(DocumentError.Unavailable())
        }
        var handle: Long? = null
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("Provider returned no file descriptor for $uri")
            // Positional reads: hashing doesn't move the offset PDFium will read from.
            val hash = try {
                computeContentHash(pfd)
            } catch (t: Throwable) {
                pfd.close()
                throw t
            }
            val opened = openPfd(pfd) // takes ownership of pfd
            handle = opened.handle
            if (recordInRecents) {
                recents.recordOpened(uri, recents.displayName(uri), hash, opened.pages.size)
                generateThumbnailIfMissing(opened.handle, hash, opened.pages.firstOrNull())
            }
            OpenResult.Success(opened)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                handle?.let { renderer().closeDocument(it) }
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                handle?.let { runCatching { renderer().closeDocument(it) } }
            }
            Log.w(TAG, "Failed to open $uri", e)
            OpenResult.Failure(e.toDocumentError())
        }
    }

    private suspend fun openCorpusAsset(asset: String): OpenResult = withContext(Dispatchers.IO) {
        var handle: Long? = null
        try {
            val tempFile = File(cacheDir, "corpus_current.pdf")
            assets.open(asset).use { input -> FileOutputStream(tempFile).use { input.copyTo(it) } }
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val opened = openPfd(pfd)
            handle = opened.handle
            OpenResult.Success(opened)
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
            OpenResult.Failure(e.toDocumentError())
        }
    }

    private suspend fun openPfd(pfd: ParcelFileDescriptor): OpenedDocument {
        val r = renderer()
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
        private const val KEY_OPEN_URI = "open_uri"
        private const val KEY_RECORD_IN_RECENTS = "record_in_recents"
    }
}
