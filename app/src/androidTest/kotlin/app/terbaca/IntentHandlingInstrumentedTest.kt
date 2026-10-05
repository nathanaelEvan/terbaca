package app.terbaca

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.terbaca.core.data.RecentDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntentHandlingInstrumentedTest {

    @Test
    fun extractPdfUri_viewIntent_returnsDataUri() {
        val uri = Uri.parse("content://app.terbaca/test.pdf")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        assertEquals(uri, extractPdfUri(intent))
    }

    @Test
    fun extractPdfUri_sendIntent_withExtraStream_returnsUri() {
        val uri = Uri.parse("content://app.terbaca/shared.pdf")
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        assertEquals(uri, extractPdfUri(intent))
    }

    @Test
    fun extractPdfUri_sendIntent_withClipData_returnsUri() {
        val uri = Uri.parse("content://app.terbaca/shared_clip.pdf")
        val intent = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("PDF", uri)
        }
        assertEquals(uri, extractPdfUri(intent))
    }

    @Test
    fun extractPdfUri_mainIntent_returnsNull() {
        val intent = Intent(Intent.ACTION_MAIN)
        assertNull(extractPdfUri(intent))
    }

    @Test
    fun extractPdfUri_null_returnsNull() {
        assertNull(extractPdfUri(null))
    }

    @Test
    fun tapRecent_afterDocumentOpenedFromIntent_opensRecentDocument() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val initialUri = Uri.parse("content://app.terbaca.test/intent_doc.pdf")
        val intent = Intent(Intent.ACTION_VIEW, initialUri).apply {
            setClass(context, MainActivity::class.java)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Initial intent was processed and requested to open
                assertEquals(initialUri.toString(), activity.lastRequestedOpenUri)

                // Simulate user tapping a recent document after returning to library
                val recentDoc = RecentDocument(
                    uri = "content://app.terbaca.test/recent_doc.pdf",
                    contentHash = "hash123",
                    displayName = "recent_doc.pdf",
                    pageCount = 5,
                    lastPage = 0,
                    lastOpenedAtMillis = 1000L
                )
                activity.openRecentHandler?.invoke(recentDoc)

                // Verify the recent document is requested to open despite intentToProcess being non-null
                assertEquals("content://app.terbaca.test/recent_doc.pdf", activity.lastRequestedOpenUri)
            }
        }
    }
}
