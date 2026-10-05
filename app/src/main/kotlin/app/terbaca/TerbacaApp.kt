package app.terbaca

import android.app.Application
import app.terbaca.core.data.RecentsRepository
import app.terbaca.core.render.cache.ThumbnailDiskCache
import java.io.File

class TerbacaApp : Application() {
    // Lazy so cold start doesn't open the database before the first frame.
    val recentsRepository: RecentsRepository by lazy { RecentsRepository.create(this) }
    val thumbnailDiskCache: ThumbnailDiskCache by lazy {
        ThumbnailDiskCache(File(cacheDir, "thumbnails"))
    }
}
