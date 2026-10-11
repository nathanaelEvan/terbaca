package app.terbaca.core.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform

/** A library entry plus whether its file can still be opened (F-OPEN-4). */
data class RecentEntry(val document: RecentDocument, val isAvailable: Boolean)

/**
 * Recents list (F-LIB-1) backed by Room, with persisted SAF read permissions (F-OPEN-4), and
 * per-document reading state keyed by content hash (F-LIB-2).
 */
class RecentsRepository(
    context: Context,
    private val dao: RecentDocumentDao,
    private val stateDao: DocumentStateDao,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val resolver = context.applicationContext.contentResolver

    val recents: Flow<List<RecentEntry>> = dao.observeAll()
        .transform { documents ->
            val initial = documents.map { RecentEntry(it, isPermissionPersisted(Uri.parse(it.uri))) }
            emit(initial)
            if (documents.isNotEmpty()) {
                val checked = documents.map { RecentEntry(it, isAvailable(Uri.parse(it.uri))) }
                if (checked != initial) {
                    emit(checked)
                }
            }
        }
        .flowOn(Dispatchers.IO)

    /** Returns true if read access to [uri] is already held among persisted permissions. */
    fun isPermissionPersisted(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /**
     * Keeps read access to [uri] across restarts. Returns false when the provider doesn't offer a
     * persistable grant (e.g. some share-sheet URIs); the document then works for this session only.
     */
    fun persistReadPermission(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "No persistable permission for $uri", e)
        false
    }

    /**
     * Releases persisted read permission for [uri] if the document failed to open or was deleted.
     */
    fun releasePersistablePermission(uri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Display name from the provider, falling back to the last path segment. */
    fun displayName(uri: Uri): String {
        val queried = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    /**
     * Resolves display title: prefers PDF metadata title when present and non-blank;
     * falls back to [fallbackDisplayName] (F-LIB-1).
     */
    fun resolveTitle(metadataTitle: String?, fallbackDisplayName: String): String =
        app.terbaca.core.data.resolveTitle(metadataTitle, fallbackDisplayName)

    /**
     * Records [uri] in the library. [lastPage] and [readingMode] come from [loadState], which must
     * run first: this replaces the URI's entry, and with it the previous hash of a file edited in
     * place.
     */
    suspend fun recordOpened(
        uri: Uri,
        displayName: String,
        contentHash: String,
        pageCount: Int,
        lastPage: Int = 0,
        readingMode: String? = null
    ): RecentDocument {
        val doc = RecentDocument(
            uri = uri.toString(),
            contentHash = contentHash,
            displayName = displayName,
            pageCount = pageCount,
            lastPage = lastPage,
            lastOpenedAtMillis = now(),
            readingMode = readingMode
        )
        dao.upsert(doc)
        trimToLimit()
        return doc
    }

    /**
     * The saved state of the document with [contentHash] (F-LIB-2), from any URI it was read at.
     *
     * Without one, falls back to the page and mode of a library entry: [uri]'s entry (a file
     * edited in place has a new hash), else the newest entry with [contentHash] (saved before
     * `document_state` existed). The viewport isn't carried over, as the layout may have changed.
     */
    suspend fun loadState(uri: Uri, contentHash: String): DocumentState? {
        stateDao.get(contentHash)?.let { return it }
        val entry = dao.getByUri(uri.toString()) ?: dao.getByContentHash(contentHash) ?: return null
        return DocumentState(contentHash, page = entry.lastPage, readingMode = entry.readingMode)
    }

    /** Saves [state] and mirrors its page and mode onto the library entries of that content. */
    suspend fun saveState(state: DocumentState) {
        stateDao.upsert(state)
        dao.updateStateByContentHash(state.contentHash, state.page, state.readingMode)
    }

    suspend fun remove(document: RecentDocument) {
        dao.delete(document.uri)
        if (dao.countByUri(document.uri) == 0) {
            releasePermission(Uri.parse(document.uri))
        }
    }

    // Android caps persisted URI grants per app; keep the list (and grants) bounded.
    private suspend fun trimToLimit() {
        val all = dao.observeAll().first()
        all.drop(MAX_RECENTS).forEach { remove(it) }
    }

    private fun releasePermission(uri: Uri) {
        runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun isAvailable(uri: Uri): Boolean {
        if (!isPermissionPersisted(uri)) return false
        // A moved or deleted file no longer resolves at its provider.
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() }
        }.getOrNull() == true
    }

    companion object {
        private const val TAG = "RecentsRepository"
        const val MAX_RECENTS = 100

        fun create(context: Context): RecentsRepository {
            val db = RecentsDatabase.create(context)
            return RecentsRepository(context, db.recentDocumentDao(), db.documentStateDao())
        }
    }
}

/**
 * Resolves display title: prefers PDF metadata title when present and meaningful;
 * falls back to [fallbackDisplayName] (F-LIB-1). Titles that authoring tools write by default
 * ("Microsoft Word - Document1.docx", "Untitled", "PowerPoint Presentation") are ignored (#38).
 */
fun resolveTitle(metadataTitle: String?, fallbackDisplayName: String): String =
    metadataTitle?.trim()?.takeIf { it.isNotEmpty() && !isJunkTitle(it) } ?: fallbackDisplayName

private val JUNK_TITLE_PREFIXES = listOf("microsoft word -", "microsoft powerpoint -", "microsoft excel -")
private val JUNK_TITLES = Regex(
    """untitled.*|(powerpoint )?presentation\d*|document\d*|slide \d+|title|no title|""" +
        """\(?(anonymous|unknown)\)?|.*\.(docx?|pptx?|xlsx?|odt|odp|rtf|txt|tex|dvi|ps|pdf|indd|pages|key)""",
    RegexOption.IGNORE_CASE
)

internal fun isJunkTitle(title: String): Boolean {
    val lower = title.lowercase()
    return JUNK_TITLE_PREFIXES.any { lower.startsWith(it) } || JUNK_TITLES.matches(title)
}

