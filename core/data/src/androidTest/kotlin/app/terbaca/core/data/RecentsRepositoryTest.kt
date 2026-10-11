package app.terbaca.core.data

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentsRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: RecentsDatabase
    private lateinit var repository: RecentsRepository
    private var clock = 0L

    private val uriA = Uri.parse("content://test/a.pdf")
    private val uriB = Uri.parse("content://other/copy-of-a.pdf")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RecentsDatabase::class.java).build()
        repository = RecentsRepository(context, db.recentDocumentDao(), db.documentStateDao()) { ++clock }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun savedState_isRestoredFromAnotherUri() = runTest {
        repository.recordOpened(uriA, "a.pdf", "hash", pageCount = 40)
        val saved = DocumentState("hash", page = 12, readingMode = null, zoom = 2.5f, offsetX = 120f, offsetY = 9_000f)
        repository.saveState(saved)

        // Same bytes, opened from somewhere else (F-LIB-2).
        assertEquals(saved, repository.loadState(uriB, "hash"))
    }

    @Test
    fun savedState_withoutLibraryEntry_isRestored() = runTest {
        // A provider without a persistable grant: never recorded in recents.
        val saved = DocumentState("hash", page = 5, readingMode = "SLIDE")
        repository.saveState(saved)
        assertEquals(saved, repository.loadState(uriA, "hash"))
    }

    @Test
    fun saveState_mirrorsPageAndModeOntoLibraryEntries() = runTest {
        repository.recordOpened(uriA, "a.pdf", "hash", pageCount = 40)
        repository.recordOpened(uriB, "a.pdf", "hash", pageCount = 40)
        repository.saveState(DocumentState("hash", page = 30, readingMode = "SLIDE"))

        for (uri in listOf(uriA, uriB)) {
            val entry = db.recentDocumentDao().getByUri(uri.toString())
            assertEquals(30, entry?.lastPage)
            assertEquals("SLIDE", entry?.readingMode)
        }
    }

    @Test
    fun noState_fallsBackToSameUriEntry_editedInPlace() = runTest {
        repository.recordOpened(uriA, "a.pdf", "old-hash", pageCount = 40, lastPage = 17, readingMode = "SLIDE")
        // The file was edited in place: same URI, new bytes. Page and mode carry over, the
        // viewport doesn't (the layout may have changed).
        assertEquals(
            DocumentState("new-hash", page = 17, readingMode = "SLIDE"),
            repository.loadState(uriA, "new-hash")
        )
    }

    @Test
    fun noState_fallsBackToNewestEntryWithHash() = runTest {
        // Entries saved before document_state existed.
        repository.recordOpened(uriA, "a.pdf", "hash", pageCount = 40, lastPage = 3)
        repository.recordOpened(uriB, "a.pdf", "hash", pageCount = 40, lastPage = 8, readingMode = "SLIDE")
        assertEquals(
            DocumentState("hash", page = 8, readingMode = "SLIDE"),
            repository.loadState(Uri.parse("content://third/a.pdf"), "hash")
        )
    }

    @Test
    fun unknownDocument_hasNoState() = runTest {
        assertNull(repository.loadState(uriA, "hash"))
    }

    @Test
    fun recordOpened_keepsGivenStateOnEntry() = runTest {
        val entry = repository.recordOpened(uriA, "a.pdf", "hash", pageCount = 40, lastPage = 6, readingMode = "SLIDE")
        assertEquals(entry, db.recentDocumentDao().getByUri(uriA.toString()))
        assertEquals(6, entry.lastPage)
        assertEquals("SLIDE", entry.readingMode)
    }

    @Test
    fun recents_emitsInitialStateImmediately() = runTest {
        repository.recordOpened(uriA, "a.pdf", "hash", pageCount = 40)
        val firstEmission = kotlinx.coroutines.flow.first(repository.recents)
        assertEquals(1, firstEmission.size)
        assertEquals(uriA.toString(), firstEmission.first().document.uri)
    }
}
