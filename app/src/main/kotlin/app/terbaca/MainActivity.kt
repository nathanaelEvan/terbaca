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
import androidx.annotation.VisibleForTesting
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
import app.terbaca.core.data.DocumentState
import app.terbaca.core.data.DocumentStateWriter
import app.terbaca.core.data.RecentDocument
import app.terbaca.core.data.computeContentHash
import app.terbaca.core.data.resolveTitle
import app.terbaca.core.model.PageInfo
import app.terbaca.core.model.ReadingMode
import app.terbaca.core.model.autoDetectReadingMode
import app.terbaca.core.pdfium.PdfiumRemotePageRenderer
import app.terbaca.core.render.PageRenderer
import app.terbaca.core.render.PerfTrace
import app.terbaca.core.render.cache.ThumbnailDiskCache
import app.terbaca.core.render.cache.ThumbnailPipeline
import app.terbaca.core.ui.theme.TerbacaTheme
import app.terbaca.feature.library.LibraryScreen
import app.terbaca.feature.reader.ReaderScreen
import app.terbaca.feature.reader.ReaderViewport
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
    private class OpenedDocument(
        val handle: Long,
        val pages: List<PageInfo>,
        val initialPage: Int = 0,
        val savedMode: ReadingMode? = null,
        val contentHash: String? = null,
        val savedViewport: ReaderViewport? = null,
        val title: String = ""
    )

    private sealed interface OpenResult {
        data class Success(val doc: OpenedDocument) : OpenResult
        data class Failure(val error: DocumentError) : OpenResult
    }

    private var opened by mutableStateOf<OpenedDocument?>(null)
    internal var openUriString by mutableStateOf<String?>(null)
        private set
    internal var currentDocumentError by mutableStateOf<DocumentError?>(null)
        private set
    internal var openRequestCount by mutableStateOf(0)
        private set
    internal var loading by mutableStateOf(false)
        @VisibleForTesting internal set

    // Bound on first open, so a cold start into the library doesn't spin up the :render process.
    private var renderer: PdfiumRemotePageRenderer? = null
    private val recents by lazy { (application as TerbacaApp).recentsRepository }
    private val thumbnailDiskCache by lazy { (application as TerbacaApp).thumbnailDiskCache }

    // App scope: the onStop flush must outlive this activity (F-LIB-2).
    private val stateWriter by lazy {
        DocumentStateWriter((application as TerbacaApp).appScope) { recents.saveState(it) }
    }
    private val thumbnailPipelineLazy = lazy {
        ThumbnailPipeline(diskCache = thumbnailDiskCache, renderer = renderer())
    }
    private val thumbnailPipeline by thumbnailPipelineLazy

    private fun renderer(): PdfiumRemotePageRenderer =
        renderer ?: PdfiumRemotePageRenderer(applicationContext).also { renderer = it }

    private fun closeCurrentDocument() {
        val doc = opened
        opened = null
        loading = false
        if (doc != null) {
            lifecycleScope.launch(NonCancellable) {
                renderer?.closeDocument(doc.handle)
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            val uri = extractPdfUri(intent)
            if (uri != null) {
                if (!isAllowedPdfScheme(uri.scheme)) {
                    closeCurrentDocument()
                    openUriString = null
                    loading = false
                    currentDocumentError = DocumentError.Unavailable()
                    return
                }
                currentDocumentError = null
                openUriString = uri.toString()
                openRequestCount++
            }
        }
    }

    internal fun openRecent(recent: RecentDocument) {
        openUriString = recent.uri
        openRequestCount++
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (thumbnailPipelineLazy.isInitialized()) thumbnailPipeline.onTrimMemory(level)
    }

    /** Queues the reading state of [doc] for a debounced save, keyed by content hash (F-LIB-2). */
    private fun saveState(doc: OpenedDocument, position: ReadingPosition, mode: ReadingMode?) {
        val hash = doc.contentHash ?: return
        val viewport = position.viewport
        stateWriter.submit(
            DocumentState(hash, position.currentPage, mode?.name, viewport?.zoom, viewport?.x, viewport?.y)
        )
    }

    override fun onStop() {
        super.onStop()
        stateWriter.flush()
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_OPEN_URI, openUriString)
        outState.putString(KEY_DOCUMENT_ERROR, currentDocumentError?.key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The pages run under the gesture bar: no scrim behind it, so it floats over the page.
        window.isNavigationBarContrastEnforced = false
        requestHighestRefreshRate()

        if (savedInstanceState != null) {
            openUriString = savedInstanceState.getString(KEY_OPEN_URI)
            currentDocumentError = DocumentError.fromKey(savedInstanceState.getString(KEY_DOCUMENT_ERROR))
        } else {
            handleIncomingIntent(intent)
        }

        // Benchmarks and baseline-profile generation open a bundled corpus file directly.
        val corpusAsset = intent?.getStringExtra(EXTRA_CORPUS_FILE)
        loading = corpusAsset != null || (openUriString != null && currentDocumentError == null)
        if (corpusAsset != null) PerfTrace.beginOpen()

        setContent {
            TerbacaTheme {
                var userModeOverride by remember { mutableStateOf<ReadingMode?>(null) }
                val scope = rememberCoroutineScope()

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        currentDocumentError = null
                        openUriString = uri.toString()
                        openRequestCount++
                    }
                }

                LaunchedEffect(corpusAsset) {
                    if (corpusAsset != null) {
                        loading = true
                        currentDocumentError = null
                        when (val result = openCorpusAsset(corpusAsset)) {
                            is OpenResult.Success -> {
                                opened = result.doc
                                currentDocumentError = null
                            }
                            is OpenResult.Failure -> {
                                opened = null
                                currentDocumentError = result.error
                            }
                        }
                        loading = false
                    }
                }

                LaunchedEffect(openUriString, openRequestCount) {
                    val uriStr = openUriString ?: return@LaunchedEffect
                    if (corpusAsset != null) return@LaunchedEffect
                    userModeOverride = null
                    if (opened != null) {
                        val oldDoc = opened
                        opened = null
                        withContext(NonCancellable) {
                            oldDoc?.let { renderer().closeDocument(it.handle) }
                        }
                    }
                    loading = true
                    currentDocumentError = null
                    PerfTrace.beginOpen()
                    try {
                        Log.d(TAG, "Opening document: $uriStr (request #$openRequestCount)")
                        val result = openFromUri(Uri.parse(uriStr))
                        if (openUriString == uriStr) {
                            when (result) {
                                is OpenResult.Success -> {
                                    opened = result.doc
                                    currentDocumentError = null
                                }
                                is OpenResult.Failure -> {
                                    opened = null
                                    currentDocumentError = result.error
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

                val backToLibrary: () -> Unit = {
                    opened?.let { doc ->
                        lifecycleScope.launch {
                            withContext(NonCancellable) {
                                renderer?.closeDocument(doc.handle)
                            }
                        }
                    }
                    opened = null
                    currentDocumentError = null
                    openUriString = null
                    userModeOverride = null
                    loading = false
                }
                BackHandler(
                    enabled = opened != null || currentDocumentError != null || openUriString != null || loading,
                    onBack = backToLibrary
                )

                val doc = opened
                val err = currentDocumentError
                when {
                    doc != null -> {
                        val currentMode = userModeOverride ?: doc.savedMode ?: remember(doc.pages) {
                            autoDetectReadingMode(doc.pages)
                        }
                        // Keyed by the opened document, so a newly opened file starts at its own page.
                        val position = remember(doc) { ReadingPosition(doc.initialPage, doc.savedViewport) }
                        if (currentMode == ReadingMode.SLIDE) {
                            SlidesScreen(
                                modifier = Modifier.fillMaxSize(),
                                pages = doc.pages,
                                renderer = renderer(),
                                docHandle = doc.handle,
                                initialPage = position.entryPage,
                                contentHash = doc.contentHash,
                                thumbnailPipeline = thumbnailPipeline,
                                title = doc.title,
                                onBack = backToLibrary,
                                onPageSettled = { page ->
                                    position.onPageChanged(page)
                                    saveState(doc, position, userModeOverride ?: doc.savedMode)
                                },
                                onModeToggle = {
                                    val newMode = ReadingMode.CONTINUOUS_VERTICAL
                                    position.onModeSwitch()
                                    userModeOverride = newMode
                                    saveState(doc, position, newMode)
                                }
                            )
                        } else {
                            ReaderScreen(
                                modifier = Modifier.fillMaxSize(),
                                pages = doc.pages,
                                renderer = renderer(),
                                docHandle = doc.handle,
                                initialPage = position.entryPage,
                                initialViewport = position.entryViewport,
                                contentHash = doc.contentHash,
                                thumbnailPipeline = thumbnailPipeline,
                                title = doc.title,
                                onBack = backToLibrary,
                                onPageChange = { page ->
                                    position.onPageChanged(page)
                                    saveState(doc, position, userModeOverride ?: doc.savedMode)
                                },
                                onViewportChange = { viewport ->
                                    position.onViewportChanged(viewport)
                                    saveState(doc, position, userModeOverride ?: doc.savedMode)
                                },
                                onModeToggle = {
                                    val newMode = ReadingMode.SLIDE
                                    position.onModeSwitch()
                                    userModeOverride = newMode
                                    saveState(doc, position, newMode)
                                }
                            )
                        }
                    }
                    err != null -> {
                        DocumentErrorScreen(
                            error = err,
                            onDismiss = {
                                currentDocumentError = null
                                openUriString = null
                                loading = false
                            },
                            onOpenOther = {
                                currentDocumentError = null
                                openUriString = null
                                loading = false
                                picker.launch(arrayOf(PDF_MIME_TYPE))
                            }
                        )
                    }
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    else -> {
                        val entries by recents.recents.collectAsState(initial = emptyList())
                        val thumbnailWrites by thumbnailDiskCache.writes.collectAsState()
                        LibraryScreen(
                            recents = entries,
                            onOpenDocument = { picker.launch(arrayOf(PDF_MIME_TYPE)) },
                            onRecentClick = { recent -> openRecent(recent) },
                            onRemove = { recent -> scope.launch { recents.remove(recent) } },
                            thumbnailLoader = { hash ->
                                withContext(Dispatchers.IO) {
                                    thumbnailDiskCache.get(hash, 0, ThumbnailDiskCache.DEFAULT_THUMBNAIL_WIDTH_PX)
                                }
                            },
                            thumbnailVersion = thumbnailWrites
                        )
                    }
                }
            }
        }
    }

    /** Opens a SAF/content URI, records it in recents when its grant persists, and returns an OpenResult. */
    private suspend fun openFromUri(uri: Uri): OpenResult = withContext(Dispatchers.IO) {
        if (!isAllowedPdfScheme(uri.scheme)) {
            Log.w(TAG, "Rejected non-content URI: $uri")
            return@withContext OpenResult.Failure(DocumentError.Unavailable())
        }
        var handle: Long? = null
        try {
            withPersistedPermission(
                isAlreadyPersisted = { recents.isPermissionPersisted(uri) },
                persistPermission = { recents.persistReadPermission(uri) },
                releasePermission = { recents.releasePersistablePermission(uri) }
            ) { persisted ->
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
                // Keyed by content (F-LIB-2), so it restores without a library entry too. Read
                // before recordOpened, which replaces this URI's entry (the edited-in-place fallback).
                val state = recents.loadState(uri, hash)
                val initialPage = (state?.page ?: 0).coerceIn(0, opened.pages.lastIndex)
                val displayName = resolveOpenedTitle(renderer(), opened.handle, recents.displayName(uri))
                if (persisted) {
                    // Once the row exists the grant must survive, so a cancel can't split the two.
                    withContext(NonCancellable) {
                        recents.recordOpened(uri, displayName, hash, opened.pages.size, initialPage, state?.readingMode)
                    }
                    generateThumbnailIfMissing(opened.handle, hash, opened.pages.firstOrNull())
                }
                val savedMode = state?.readingMode?.let {
                    runCatching { ReadingMode.valueOf(it) }.getOrNull()
                }
                OpenResult.Success(
                    OpenedDocument(
                        opened.handle, opened.pages, initialPage, savedMode, hash, state?.readerViewport(), displayName
                    )
                )
            }
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
            OpenResult.Success(OpenedDocument(opened.handle, opened.pages, title = asset))
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
        private const val KEY_DOCUMENT_ERROR = "document_error"
    }
}

/** The saved scroll-mode viewport, if the state has one (F-LIB-2). */
private fun DocumentState.readerViewport(): ReaderViewport? {
    val zoom = zoom ?: return null
    val x = offsetX ?: return null
    val y = offsetY ?: return null
    return ReaderViewport(zoom, x, y)
}

/**
 * Executes [block] with read permission management.
 *
 * Persists read permission unless it is already persisted. If [block] fails or is cancelled,
 * releases the grant only when this call took it, so an open that never reached recents doesn't
 * leave a dangling grant. Grants that existed before are never released.
 */
internal suspend fun <T> withPersistedPermission(
    isAlreadyPersisted: () -> Boolean,
    persistPermission: () -> Boolean,
    releasePermission: () -> Unit,
    block: suspend (isPersisted: Boolean) -> T
): T {
    val alreadyPersisted = isAlreadyPersisted()
    val newlyPersisted = !alreadyPersisted && persistPermission()
    val persisted = alreadyPersisted || newlyPersisted
    return try {
        block(persisted)
    } catch (e: Throwable) {
        if (newlyPersisted) {
            releasePermission()
        }
        throw e
    }
}

/**
 * Safely extracts document metadata title, falling back to [fallbackDisplayName]
 * if the lookup fails or returns blank (F-LIB-1). Rethrows [CancellationException].
 */
internal suspend fun resolveOpenedTitle(
    renderer: PageRenderer,
    docHandle: Long,
    fallbackDisplayName: String
): String {
    val metadataTitle = try {
        renderer.getDocumentTitle(docHandle)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        runCatching { Log.w("MainActivity", "Failed to extract metadata title for handle $docHandle", t) }
        null
    }
    return resolveTitle(metadataTitle, fallbackDisplayName)
}

