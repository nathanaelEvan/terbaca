package app.terbaca.render

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service hosting the PDFium engine inside a separate process (:render)
 * as required by PRD §4.2.
 */
class PdfiumRenderService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        // Will return Binder/AIDL interface in Milestone 1 (M1-03)
        return null
    }
}
