package app.terbaca.core.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentsMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecentsDatabase::class.java
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migrate2To3_keepsRecentsAndAddsDocumentState() = runTest {
        helper.createDatabase(DB_NAME, 2).use { db ->
            val row = ContentValues().apply {
                put("uri", "content://test/a.pdf")
                put("contentHash", "hash")
                put("displayName", "a.pdf")
                put("pageCount", 40)
                put("lastPage", 12)
                put("lastOpenedAtMillis", 1L)
                put("readingMode", "SLIDE")
            }
            db.insert("recent_documents", SQLiteDatabase.CONFLICT_FAIL, row)
        }

        // Validates the migrated schema against 3.json.
        helper.runMigrationsAndValidate(DB_NAME, 3, true).close()

        val db = Room.databaseBuilder(context, RecentsDatabase::class.java, DB_NAME).build()
        try {
            val repository = RecentsRepository(context, db.recentDocumentDao(), db.documentStateDao())
            assertEquals(12, db.recentDocumentDao().getByUri("content://test/a.pdf")?.lastPage)
            assertNull(db.documentStateDao().get("hash"))
            // Pre-migration page and mode still restore, from any URI.
            assertEquals(
                DocumentState("hash", page = 12, readingMode = "SLIDE"),
                repository.loadState(Uri.parse("content://elsewhere/a.pdf"), "hash")
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun migrateAll_fromVersion1() {
        helper.createDatabase(DB_NAME_V1, 1).close()
        helper.runMigrationsAndValidate(DB_NAME_V1, 3, true).close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
        const val DB_NAME_V1 = "migration-test-v1.db"
    }
}
