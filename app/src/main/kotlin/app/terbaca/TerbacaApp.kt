package app.terbaca

import android.app.Application
import android.content.ComponentCallbacks2
import app.terbaca.core.data.RecentsRepository
import app.terbaca.core.pdfium.PdfiumRemotePageRenderer
import app.terbaca.core.render.cache.ThumbnailDiskCache
import app.terbaca.core.render.cache.ThumbnailPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class TerbacaApp : Application() {
    /** Work that must outlive an activity, such as the last reading-state write in onStop. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Lazy so cold start doesn't open the database before the first frame.
    val recentsRepository: RecentsRepository by lazy { RecentsRepository.create(this) }
    val thumbnailDiskCache: ThumbnailDiskCache by lazy {
        ThumbnailDiskCache(File(cacheDir, "thumbnails"))
    }

    // Retained across activity recreation per M6-05. Bound on first open.
    @Volatile
    private var rendererInstance: PdfiumRemotePageRenderer? = null

    val thumbnailPipeline: ThumbnailPipeline by lazy {
        ThumbnailPipeline(diskCache = thumbnailDiskCache, renderer = getOrCreateRenderer())
    }

    fun getOrCreateRenderer(): PdfiumRemotePageRenderer {
        val existing = rendererInstance
        if (existing != null && !existing.isClosed) return existing
        return synchronized(this) {
            val current = rendererInstance
            if (current != null && !current.isClosed) {
                current
            } else {
                PdfiumRemotePageRenderer(this).also { rendererInstance = it }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            synchronized(this) {
                rendererInstance?.close()
                rendererInstance = null
            }
        }
    }
}
