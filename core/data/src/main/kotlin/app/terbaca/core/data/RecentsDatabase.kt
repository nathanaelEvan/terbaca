package app.terbaca.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One library entry, keyed by content hash (F-LIB-2) so moving the file keeps its history. */
@Entity(tableName = "recent_documents")
data class RecentDocument(
    @PrimaryKey val contentHash: String,
    /** Last URI the document was opened from; has a persisted read permission (F-OPEN-4). */
    val uri: String,
    val displayName: String,
    val pageCount: Int,
    val lastPage: Int,
    val lastOpenedAtMillis: Long
)

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAtMillis DESC")
    fun observeAll(): Flow<List<RecentDocument>>

    @Query("SELECT * FROM recent_documents WHERE contentHash = :contentHash")
    suspend fun get(contentHash: String): RecentDocument?

    @Upsert
    suspend fun upsert(document: RecentDocument)

    @Query("DELETE FROM recent_documents WHERE contentHash = :contentHash")
    suspend fun delete(contentHash: String)
}

@Database(entities = [RecentDocument::class], version = 1)
abstract class RecentsDatabase : RoomDatabase() {
    abstract fun recentDocumentDao(): RecentDocumentDao

    companion object {
        fun create(context: Context): RecentsDatabase =
            Room.databaseBuilder(context.applicationContext, RecentsDatabase::class.java, "recents.db")
                .build()
    }
}
