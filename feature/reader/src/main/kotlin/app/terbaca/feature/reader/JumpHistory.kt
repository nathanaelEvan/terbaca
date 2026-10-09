package app.terbaca.feature.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf

/**
 * Positions the reader left by following a link or an outline entry (F-NAV-5), newest last.
 * The back chip returns to the newest one. Bounded, so a long chain of citation hops can't grow
 * without limit; the oldest positions drop first.
 */
@Stable
class JumpHistory(private val capacity: Int = DEFAULT_CAPACITY) {
    private val entries = mutableStateListOf<JumpEntry>()

    /** The position the back chip returns to, or null when there is nowhere to go back to. */
    val latest: JumpEntry? get() = entries.lastOrNull()

    fun push(entry: JumpEntry) {
        if (entries.lastOrNull() == entry) return
        entries.add(entry)
        while (entries.size > capacity) entries.removeAt(0)
    }

    fun pop(): JumpEntry? = entries.removeLastOrNull()

    companion object {
        const val DEFAULT_CAPACITY = 32
    }
}

/** A saved reader position and the page it showed, for the chip's "Back to page N" label. */
data class JumpEntry(val viewport: ReaderViewport, val page: Int)
