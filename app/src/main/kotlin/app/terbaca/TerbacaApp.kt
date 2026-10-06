package app.terbaca

import android.app.Application
import app.terbaca.core.data.RecentsRepository
import app.terbaca.core.render.cache.ThumbnailDiskCache
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
}
