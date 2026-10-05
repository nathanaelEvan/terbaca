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

    data class Encrypted(
        override val titleRes: Int = R.string.error_encrypted_title,
        override val messageRes: Int = R.string.error_encrypted_message
    ) : DocumentError

    data class Corrupt(
        override val titleRes: Int = R.string.error_corrupt_title,
        override val messageRes: Int = R.string.error_corrupt_message
    ) : DocumentError

    data class Unavailable(
        override val titleRes: Int = R.string.error_unavailable_title,
        override val messageRes: Int = R.string.error_unavailable_message
    ) : DocumentError

    data class Generic(
        override val titleRes: Int = R.string.error_generic_title,
        override val messageRes: Int = R.string.error_generic_message
    ) : DocumentError
}

fun Throwable.toDocumentError(): DocumentError = when (this) {
    is PdfPasswordException, is PdfSecurityException -> DocumentError.Encrypted()
    is PdfCorruptException -> DocumentError.Corrupt()
    is PdfFileException, is FileNotFoundException, is SecurityException, is IOException -> DocumentError.Unavailable()
    else -> DocumentError.Generic()
}

/** Extracts a PDF URI from ACTION_VIEW or ACTION_SEND intents. */
fun extractPdfUri(intent: Intent?): Uri? {
    if (intent == null) return null
    return when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        }
        else -> null
    }
}

/**
 * Decides whether to record an opened document into recents (F-OPEN-4).
 * For ACTION_SEND with transient grants (where persistReadPermission fails), the document
 * must NOT be saved to recents as it will not survive restarts.
 */
fun shouldRecordInRecents(action: String?, isPermissionPersisted: Boolean): Boolean =
    if (action == Intent.ACTION_SEND) isPermissionPersisted else true

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
