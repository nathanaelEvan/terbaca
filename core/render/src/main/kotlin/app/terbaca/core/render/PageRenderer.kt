package app.terbaca.core.render

import app.terbaca.core.model.DocumentId
import app.terbaca.core.model.PageInfo

/**
 * Common abstraction for rendering document pages and tiles.
 * Supports both in-process and out-of-process (:render) implementations.
 */
interface PageRenderer {
    suspend fun openDocument(documentId: DocumentId): List<PageInfo>
    suspend fun closeDocument(documentId: DocumentId)
}
