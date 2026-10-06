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
import kotlinx.coroutines.flow.map

/** A library entry plus whether its file can still be opened (F-OPEN-4). */
data class RecentEntry(val document: RecentDocument, val isAvailable: Boolean)

/**
 * Recents list (F-LIB-1) backed by Room, with persisted SAF read permissions (F-OPEN-4).
 */
class RecentsRepository(
    context: Context,
    private val dao: RecentDocumentDao,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val resolver = context.applicationContext.contentResolver

    val recents: Flow<List<RecentEntry>> = dao.observeAll()
        .map { documents -> documents.map { RecentEntry(it, isAvailable(Uri.parse(it.uri))) } }
        .flowOn(Dispatchers.IO)

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

    suspend fun recordOpened(
        uri: Uri,
        displayName: String,
        contentHash: String,
        pageCount: Int
    ): RecentDocument {
        val uriString = uri.toString()
        val existingByUri = dao.getByUri(uriString)
        val existingByHash = dao.getByContentHash(contentHash)
        val lastPage = existingByUri?.lastPage ?: existingByHash?.lastPage ?: 0
        val readingMode = existingByUri?.readingMode ?: existingByHash?.readingMode
        val doc = RecentDocument(
            uri = uriString,
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

    suspend fun getExisting(uri: Uri, contentHash: String? = null): RecentDocument? {
        val uriString = uri.toString()
        return dao.getByUri(uriString) ?: contentHash?.let { dao.getByContentHash(it) }
    }

    suspend fun updateLastPage(uri: Uri, lastPage: Int) {
        dao.updateLastPage(uri.toString(), lastPage)
    }

    suspend fun updateReadingMode(uri: Uri, readingMode: String?) {
        dao.updateReadingMode(uri.toString(), readingMode)
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
        val granted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!granted) return false
        // A moved or deleted file no longer resolves at its provider.
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() }
        }.getOrNull() == true
    }

    companion object {
        private const val TAG = "RecentsRepository"
        const val MAX_RECENTS = 100

        fun create(context: Context) =
            RecentsRepository(context, RecentsDatabase.create(context).recentDocumentDao())
    }
}

/**
 * Resolves display title: prefers PDF metadata title when present and non-blank;
 * falls back to [fallbackDisplayName] (F-LIB-1).
 */
fun resolveTitle(metadataTitle: String?, fallbackDisplayName: String): String =
    metadataTitle?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackDisplayName

