package app.terbaca.feature.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.terbaca.core.model.PageLink

/**
 * The link under page point ([x], [y]) on a page with [links], or null. Overlapping links (a
 * citation inside a linked paragraph) resolve to the smallest one, which is the one the user aimed at.
 */
internal fun linkAt(links: List<PageLink>, x: Float, y: Float): PageLink? =
    links.filter { (it.isInternal || it.isExternal) && it.sourceRect.contains(x, y) }
        .minByOrNull { it.sourceRect.width * it.sourceRect.height }

private val OPENABLE_SCHEMES = setOf("http", "https", "mailto")

/**
 * True when [uri] may be handed to another app (F-NAV-6). The URI comes from an untrusted PDF, so
 * only web and mail links qualify; anything else (file, content, intent, javascript) is only shown.
 */
internal fun isOpenableUri(uri: String): Boolean =
    uri.substringBefore(':', missingDelimiterValue = "").lowercase() in OPENABLE_SCHEMES

/** Opens [uri] in another app; the reader itself has no network access. False when none can. */
internal fun openExternalUri(context: Context, uri: String): Boolean {
    if (!isOpenableUri(uri)) return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("PageLinks", "No app can open $uri", e)
        false
    }
}
