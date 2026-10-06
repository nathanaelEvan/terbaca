package app.terbaca.core.data

import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class RecentsDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: RecentsDatabase
    private lateinit var dao: RecentDocumentDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RecentsDatabase::class.java).build()
        dao = db.recentDocumentDao()
    }

    @After
    fun tearDown() = db.close()

    private fun doc(hash: String, openedAt: Long, lastPage: Int = 0, uri: String = "content://test/$hash") =
        RecentDocument(uri, hash, "$hash.pdf", pageCount = 10, lastPage, openedAt)

    @Test
    fun observeAll_newestFirst() = runTest {
        dao.upsert(doc("a", openedAt = 1))
        dao.upsert(doc("b", openedAt = 3))
        dao.upsert(doc("c", openedAt = 2))
        assertEquals(listOf("b", "c", "a"), dao.observeAll().first().map { it.contentHash })
    }

    @Test
    fun upsert_sameUri_editInPlace_replacesEntry() = runTest {
        dao.upsert(doc("hash1", openedAt = 1, lastPage = 2, uri = "content://test/doc"))
        dao.upsert(doc("hash2", openedAt = 5, lastPage = 2, uri = "content://test/doc").copy(pageCount = 15))
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("content://test/doc", all[0].uri)
        assertEquals("hash2", all[0].contentHash)
        assertEquals(15, all[0].pageCount)
        assertEquals(5L, all[0].lastOpenedAtMillis)
    }

    @Test
    fun delete_removesEntry() = runTest {
        val d = doc("a", openedAt = 1)
        dao.upsert(d)
        dao.delete(d.uri)
        assertNull(dao.getByUri(d.uri))
        assertEquals(0, dao.countByUri(d.uri))
    }

    @Test
    fun updateLastPage_updatesPosition() = runTest {
        val d = doc("hash1", openedAt = 1, lastPage = 0, uri = "content://test/doc")
        dao.upsert(d)
        dao.updateLastPage("content://test/doc", 7)
        val updated = dao.getByUri("content://test/doc")
        assertEquals(7, updated?.lastPage)
    }

    @Test
    fun updateReadingMode_updatesMode() = runTest {
        val d = doc("hash1", openedAt = 1, lastPage = 0, uri = "content://test/doc")
        dao.upsert(d)
        dao.updateReadingMode("content://test/doc", "SLIDE")
        val updated = dao.getByUri("content://test/doc")
        assertEquals("SLIDE", updated?.readingMode)
    }

    @Test
    fun computeContentHash_readsRealDescriptor_withoutMovingOffset() {
        val bytes = Random(7).nextBytes(200 * 1024)
        val file = File(context.cacheDir, "hash_test.bin").apply { writeBytes(bytes) }
        val copy = File(context.cacheDir, "hash_test_copy.bin").apply { writeBytes(bytes) }
        val expected = contentHash(bytes.size.toLong()) { buf, off ->
            bytes.copyInto(buf, 0, off.toInt(), off.toInt() + buf.size); buf.size
        }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            assertEquals(expected, computeContentHash(pfd))
            // A different path with the same bytes is the same document (F-LIB-2).
            ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY).use {
                assertEquals(expected, computeContentHash(it))
            }
            // Positional reads leave the offset at 0 for PDFium.
            assertEquals(0L, android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_CUR))
        }
    }
}
