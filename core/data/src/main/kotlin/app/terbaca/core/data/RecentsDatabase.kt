package app.terbaca.core.data

import android.content.Context
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
 * One library entry, keyed by URI (F-OPEN-4) so editing in place updates the existing entry,
 * with an index on contentHash so moving a file can restore its reading state (F-LIB-2).
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
    val lastOpenedAtMillis: Long
)

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY lastOpenedAtMillis DESC")
    fun observeAll(): Flow<List<RecentDocument>>

    @Query("SELECT * FROM recent_documents WHERE uri = :uri")
    suspend fun getByUri(uri: String): RecentDocument?

    @Query("SELECT * FROM recent_documents WHERE contentHash = :contentHash")
    suspend fun getByContentHash(contentHash: String): RecentDocument?

    @Upsert
    suspend fun upsert(document: RecentDocument)

    @Query("DELETE FROM recent_documents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("SELECT COUNT(*) FROM recent_documents WHERE uri = :uri")
    suspend fun countByUri(uri: String): Int
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
