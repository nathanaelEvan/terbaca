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

    suspend fun recordOpened(uri: Uri, displayName: String, contentHash: String, pageCount: Int) {
        val existing = dao.get(contentHash)
        if (existing != null && existing.uri != uri.toString()) {
            releasePermission(Uri.parse(existing.uri))
        }
        dao.upsert(
            RecentDocument(
                contentHash = contentHash,
                uri = uri.toString(),
                displayName = displayName,
                pageCount = pageCount,
                lastPage = existing?.lastPage ?: 0,
                lastOpenedAtMillis = now()
            )
        )
        trimToLimit()
    }

    suspend fun remove(document: RecentDocument) {
        dao.delete(document.contentHash)
        releasePermission(Uri.parse(document.uri))
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
