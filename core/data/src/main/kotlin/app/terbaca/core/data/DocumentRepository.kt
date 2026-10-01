package app.terbaca.core.data

import app.terbaca.core.model.DocumentId

/**
 * Repository interface for managing recents and per-document state.
 * Implemented in Milestone 2 (M2-01).
 */
interface DocumentRepository {
    suspend fun getRecentDocuments(): List<DocumentId>
}
