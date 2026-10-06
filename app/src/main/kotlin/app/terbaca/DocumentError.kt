package app.terbaca

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import app.terbaca.core.pdfium.PdfCorruptException
import app.terbaca.core.pdfium.PdfFileException
import app.terbaca.core.pdfium.PdfPasswordException
import app.terbaca.core.pdfium.PdfSecurityException
import java.io.FileNotFoundException
import java.io.IOException

sealed interface DocumentError {
    @get:StringRes
    val titleRes: Int
    @get:StringRes
    val messageRes: Int
    val key: String

    data class Encrypted(
        override val titleRes: Int = R.string.error_encrypted_title,
        override val messageRes: Int = R.string.error_encrypted_message,
        override val key: String = KEY_ENCRYPTED
    ) : DocumentError

    data class Corrupt(
        override val titleRes: Int = R.string.error_corrupt_title,
        override val messageRes: Int = R.string.error_corrupt_message,
        override val key: String = KEY_CORRUPT
    ) : DocumentError

    data class Unavailable(
        override val titleRes: Int = R.string.error_unavailable_title,
        override val messageRes: Int = R.string.error_unavailable_message,
        override val key: String = KEY_UNAVAILABLE
    ) : DocumentError

    data class Generic(
        override val titleRes: Int = R.string.error_generic_title,
        override val messageRes: Int = R.string.error_generic_message,
        override val key: String = KEY_GENERIC
    ) : DocumentError

    companion object {
        const val KEY_ENCRYPTED = "encrypted"
        const val KEY_CORRUPT = "corrupt"
        const val KEY_UNAVAILABLE = "unavailable"
        const val KEY_GENERIC = "generic"

        fun fromKey(key: String?): DocumentError? = when (key) {
            KEY_ENCRYPTED -> Encrypted()
            KEY_CORRUPT -> Corrupt()
            KEY_UNAVAILABLE -> Unavailable()
            KEY_GENERIC -> Generic()
            else -> null
        }
    }
}

fun Throwable.toDocumentError(): DocumentError = when (this) {
    is PdfPasswordException, is PdfSecurityException -> DocumentError.Encrypted()
    is PdfCorruptException -> DocumentError.Corrupt()
    is PdfFileException, is FileNotFoundException, is SecurityException, is IOException -> DocumentError.Unavailable()
    else -> DocumentError.Generic()
}

/**
 * Security: Accept only content:// schemes (F-OPEN-1 / Confused Deputy mitigation).
 * Non-content schemes (file://, http://, etc.) are treated as Unavailable.
 */
fun isAllowedPdfScheme(scheme: String?): Boolean =
    "content".equals(scheme, ignoreCase = true)

/** Extracts a PDF URI from ACTION_VIEW or ACTION_SEND intents. */
fun extractPdfUri(intent: Intent?): Uri? {
    if (intent == null) return null
    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    return when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        }
        else -> null
    }
}

/** Friendly error screen for encrypted, corrupt, and unavailable PDF documents (F-OPEN-5). */
@Composable
fun DocumentErrorScreen(
    error: DocumentError,
    onDismiss: () -> Unit,
    onOpenOther: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text(
                    text = stringResource(error.titleRes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(error.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.error_back_to_library))
                    }
                    Button(onClick = onOpenOther) {
                        Text(stringResource(R.string.error_open_other))
                    }
                }
            }
        }
    }
}
