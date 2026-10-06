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
    fun getByContentHash_returnsMostRecentlyOpened() = runTest {
        dao.upsert(doc("same", openedAt = 1, lastPage = 1, uri = "content://test/old"))
        dao.upsert(doc("same", openedAt = 9, lastPage = 9, uri = "content://test/new"))
        dao.upsert(doc("same", openedAt = 5, lastPage = 5, uri = "content://test/mid"))
        assertEquals("content://test/new", dao.getByContentHash("same")?.uri)
    }

    @Test
    fun updateStateByContentHash_updatesEveryEntryOfThatContent() = runTest {
        dao.upsert(doc("same", openedAt = 1, uri = "content://test/a"))
        dao.upsert(doc("same", openedAt = 2, uri = "content://test/b"))
        dao.upsert(doc("other", openedAt = 3))
        dao.updateStateByContentHash("same", lastPage = 7, readingMode = "SLIDE")
        assertEquals(7, dao.getByUri("content://test/a")?.lastPage)
        assertEquals("SLIDE", dao.getByUri("content://test/b")?.readingMode)
        assertEquals(0, dao.getByContentHash("other")?.lastPage)
        assertNull(dao.getByContentHash("other")?.readingMode)
    }

    @Test
    fun documentState_upsertReplacesByHash() = runTest {
        val stateDao = db.documentStateDao()
        stateDao.upsert(DocumentState("h", page = 2, readingMode = null, zoom = 1.5f, offsetX = 10f, offsetY = 900f))
        stateDao.upsert(DocumentState("h", page = 3, readingMode = "SLIDE"))
        assertEquals(DocumentState("h", page = 3, readingMode = "SLIDE"), stateDao.get("h"))
        assertNull(stateDao.get("missing"))
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
