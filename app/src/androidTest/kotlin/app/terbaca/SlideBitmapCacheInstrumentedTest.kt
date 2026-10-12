package app.terbaca

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.terbaca.feature.slides.SLIDE_CACHE_ENTRIES
import app.terbaca.feature.slides.SlideKey
import app.terbaca.feature.slides.createSlideBitmapCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lives in :app because CI only runs :app and :core:pdfium instrumented tests; it exercises
 * :feature:slides' bitmap cache with real Bitmaps, which plain JVM unit tests can't create.
 */
@RunWith(AndroidJUnit4::class)
class SlideBitmapCacheInstrumentedTest {

    private fun slide(page: Int) = SlideKey(pageIndex = page, widthPx = 32, heightPx = 18)

    @Test
    fun evictingAStillDisplayedSlide_doesNotRecycleIt() {
        val cache = createSlideBitmapCache()
        // A pager page that is still composed holds this bitmap and keeps drawing it.
        val displayed = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        cache.put(slide(0), displayed)

        for (page in 1..SLIDE_CACHE_ENTRIES) {
            cache.put(slide(page), Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888))
        }

        assertNull("page 0 should have been evicted", cache.get(slide(0)))
        assertFalse(displayed.isRecycled)
        // Drawing a recycled bitmap throws "trying to use a recycled bitmap".
        Canvas(Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)).drawBitmap(displayed, 0f, 0f, null)
    }

    @Test
    fun clearingTheCache_doesNotRecycleBitmaps() {
        val cache = createSlideBitmapCache()
        val displayed = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        cache.put(slide(0), displayed)

        cache.clear()

        assertFalse(displayed.isRecycled)
    }
}
