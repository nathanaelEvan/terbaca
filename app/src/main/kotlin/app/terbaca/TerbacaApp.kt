package app.terbaca

import android.app.Application
import app.terbaca.core.data.RecentsRepository

class TerbacaApp : Application() {
    // Lazy so cold start doesn't open the database before the first frame.
    val recentsRepository: RecentsRepository by lazy { RecentsRepository.create(this) }
}
