package app.terbaca.core.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One library entry, keyed by URI (F-OPEN-4) so editing in place updates the existing entry.
 * [lastPage] and [readingMode] mirror the [DocumentState] of [contentHash] for the library card;
 * the reading state itself lives in `document_state`, keyed by content hash (F-LIB-2).
 */
@Entity(
    tableName = "recent_documents",
    indices = [Index(value = ["contentHash"])]
)
data class RecentDocument(
    @PrimaryKey val uri: String,
    val contentHash: String,
    val displayName: String,
    val pageCount: Int,
    val lastPage: Int,
    val lastOpenedAtMillis: Long,
    val readingMode: String? = null
)

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAtMillis DESC")
    fun observeAll(): Flow<List<RecentDocument>>

    @Query("SELECT * FROM recent_documents WHERE uri = :uri")
    suspend fun getByUri(uri: String): RecentDocument?

    /** The most recently opened entry with [contentHash] (the same file may sit at several URIs). */
    @Query(
        "SELECT * FROM recent_documents WHERE contentHash = :contentHash " +
            "ORDER BY lastOpenedAtMillis DESC LIMIT 1"
    )
    suspend fun getByContentHash(contentHash: String): RecentDocument?

    @Upsert
    suspend fun upsert(document: RecentDocument)

    /** Mirrors a document's state onto every library entry of that content (F-LIB-2). */
    @Query(
        "UPDATE recent_documents SET lastPage = :lastPage, readingMode = :readingMode " +
            "WHERE contentHash = :contentHash"
    )
    suspend fun updateStateByContentHash(contentHash: String, lastPage: Int, readingMode: String?)

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("SELECT COUNT(*) FROM recent_documents WHERE uri = :uri")
    suspend fun countByUri(uri: String): Int
}

/**
 * Per-document reading state (F-LIB-2), keyed by content hash so it survives the file being
 * reopened from another URI, and kept for documents that never get a library entry (no
 * persistable grant).
 *
 * The viewport is resolution independent: [zoom] is relative to fit-width (1 = fit-width), and
 * [offsetX]/[offsetY] are the document point (PDF points in the continuous layout, which is fixed
 * for given content) at the top-left of the viewport. All three are null when there is no
 * continuous-mode viewport to restore (slides mode, or saved before it existed).
 */
@Entity(tableName = "document_state")
data class DocumentState(
    @PrimaryKey val contentHash: String,
    val page: Int,
    val readingMode: String? = null,
    val zoom: Float? = null,
    val offsetX: Float? = null,
    val offsetY: Float? = null
)

@Dao
interface DocumentStateDao {
    @Query("SELECT * FROM document_state WHERE contentHash = :contentHash")
    suspend fun get(contentHash: String): DocumentState?

    @Upsert
    suspend fun upsert(state: DocumentState)
}

@Database(
    entities = [RecentDocument::class, DocumentState::class],
    version = 3,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3)
    ]
)
abstract class RecentsDatabase : RoomDatabase() {
    abstract fun recentDocumentDao(): RecentDocumentDao

    abstract fun documentStateDao(): DocumentStateDao

    companion object {
        fun create(context: Context): RecentsDatabase =
            Room.databaseBuilder(context.applicationContext, RecentsDatabase::class.java, "recents.db")
                .build()
    }
}
