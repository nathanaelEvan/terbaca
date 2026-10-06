package app.terbaca.core.data

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Quiet time before a submitted state is written. */
const val STATE_WRITE_DEBOUNCE_MILLIS = 500L

/**
 * Debounces per-document state writes (F-LIB-2): a fling changes the page and viewport every
 * frame, but only the latest state is written, once nothing changed for [debounceMillis].
 * [flush] writes the pending state right away (onStop).
 *
 * [scope] must outlive the screen (an app scope), so a flush in onStop isn't cancelled when the
 * activity is destroyed. Not thread-safe: call [submit] and [flush] on [scope]'s (single) thread.
 * Writes run one at a time, in submission order.
 */
class DocumentStateWriter(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = STATE_WRITE_DEBOUNCE_MILLIS,
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val write: suspend (DocumentState) -> Unit
) {
    private val writeLock = Mutex()
    private var pending: DocumentState? = null
    private var lastSubmitAt = 0L
    private var timer: Job? = null

    fun submit(state: DocumentState) {
        // Another document's last state must not be overwritten by this one.
        val previous = pending
        if (previous != null && previous.contentHash != state.contentHash) flush()
        pending = state
        lastSubmitAt = now()
        // One timer per quiet period, extended in place rather than relaunched every frame.
        if (timer?.isActive == true) return
        timer = scope.launch {
            var wait = debounceMillis
            while (wait > 0) {
                delay(wait)
                wait = lastSubmitAt + debounceMillis - now()
            }
            timer = null
            flush()
        }
    }

    fun flush() {
        timer?.cancel()
        timer = null
        val state = pending ?: return
        pending = null
        scope.launch {
            writeLock.withLock {
                try {
                    write(state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Losing a reading position must not crash the app.
                    Log.w(TAG, "Failed to save state for ${state.contentHash}", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "DocumentStateWriter"
    }
}
