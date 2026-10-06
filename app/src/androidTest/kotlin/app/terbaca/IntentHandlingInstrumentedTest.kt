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
import org.junit.Assert.assertTrue
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
    fun extractPdfUri_historyIntent_returnsNull() {
        val uri = Uri.parse("content://app.terbaca/test.pdf")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        }
        assertNull(extractPdfUri(intent))
    }

    @Test
    fun nonContentScheme_isRejectedAsUnavailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileUri = Uri.parse("file:///data/data/app.terbaca/databases/recents.db")
        val intent = Intent(Intent.ACTION_VIEW, fileUri).apply {
            setClass(context, MainActivity::class.java)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNull(activity.openUriString)
                assertTrue(
                    "Rejected non-content scheme must set DocumentError.Unavailable",
                    activity.currentDocumentError is DocumentError.Unavailable
                )
            }
        }
    }

    @Test
    fun documentError_survivesActivityRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileUri = Uri.parse("file:///data/data/app.terbaca/databases/recents.db")
        val intent = Intent(Intent.ACTION_VIEW, fileUri).apply {
            setClass(context, MainActivity::class.java)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.currentDocumentError is DocumentError.Unavailable)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(
                    "Rejected scheme error must survive activity recreation",
                    activity.currentDocumentError is DocumentError.Unavailable
                )
            }
        }
    }

    @Test
    fun onNewIntent_withSecondUri_updatesOpenUriString() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val initialUri = Uri.parse("content://app.terbaca.test/first.pdf")
        val intent1 = Intent(Intent.ACTION_VIEW, initialUri).apply {
            setClass(context, MainActivity::class.java)
        }
        ActivityScenario.launch<MainActivity>(intent1).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(initialUri.toString(), activity.openUriString)
            }
            val secondUri = Uri.parse("content://app.terbaca.test/second.pdf")
            val intent2 = Intent(Intent.ACTION_VIEW, secondUri)
            scenario.onActivity { activity ->
                activity.onNewIntent(intent2)
            }
            scenario.onActivity { activity ->
                assertEquals(secondUri.toString(), activity.openUriString)
            }
        }
    }

    @Test
    fun resendingSameUri_incrementsRequestCounterToAllowRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://app.terbaca.test/retry_doc.pdf")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setClass(context, MainActivity::class.java)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var initialCount = 0
            scenario.onActivity { activity ->
                assertEquals(uri.toString(), activity.openUriString)
                initialCount = activity.openRequestCount
            }
            val retryIntent = Intent(Intent.ACTION_VIEW, uri)
            scenario.onActivity { activity ->
                activity.onNewIntent(retryIntent)
            }
            scenario.onActivity { activity ->
                assertEquals(uri.toString(), activity.openUriString)
                assertTrue(
                    "Request count must increment on re-sending same URI",
                    activity.openRequestCount > initialCount
                )
            }
        }
    }

    @Test
    fun openRecent_requestsDocumentOpenAndIncrementsCounter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClass(context, MainActivity::class.java)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val recentDoc = RecentDocument(
                    uri = "content://app.terbaca.test/recent_doc.pdf",
                    contentHash = "hash123",
                    displayName = "recent_doc.pdf",
                    pageCount = 5,
                    lastPage = 0,
                    lastOpenedAtMillis = 1000L
                )
                val prevCount = activity.openRequestCount
                activity.openRecent(recentDoc)
                assertEquals(recentDoc.uri, activity.openUriString)
                assertEquals(prevCount + 1, activity.openRequestCount)
            }
        }
    }
}
