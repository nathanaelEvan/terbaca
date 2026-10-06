package app.terbaca.core.render.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.GuardedBy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Disk cache for document page thumbnails per PRD §5.
 *
 * - Location: app cache directory (e.g. `cacheDir/thumbnails`).
 * - Storage format: WebP lossy (~85% quality) per PRD §5.
 * - Keying: [contentHash] + [pageIndex] + [sizePx] (e.g. `"{hash}_{page}_{size}.webp"`).
 * - Eviction: LRU by file modification time when cache exceeds [maxBytes] (default 200 MB).
 * - Thread-safe and atomic writes: each write goes to its own `*.tmp` file, then is renamed.
 *   Eviction only considers finished `.webp` files; temp files left by a killed process are
 *   deleted when the directory is first scanned.
 * - The directory is scanned lazily on first use, so constructing the cache does no disk I/O.
 */
class ThumbnailDiskCache(
    val directory: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val writer: (Bitmap, File) -> Boolean = { bitmap, file ->
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
        }
    },
    private val reader: (File) -> Bitmap? = { file ->
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.HARDWARE
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }
) {
    private val lock = Any()

    @GuardedBy("lock")
    private var totalBytes: Long = 0L

    @GuardedBy("lock")
    private var scanned = false

    private val writeCount = MutableStateFlow(0)

    /** Incremented after every successful [put], so a UI can reload a thumbnail that was missing. */
    val writes: StateFlow<Int> = writeCount.asStateFlow()

    fun key(contentHash: String, pageIndex: Int, sizePx: Int): String =
        "${contentHash}_${pageIndex}_$sizePx"

    private fun fileForKey(key: String): File = File(directory, "$key$FILE_SUFFIX")

    /** Returns cached thumbnail bitmap, updating its access time for LRU tracking. */
    fun get(contentHash: String, pageIndex: Int, sizePx: Int): Bitmap? {
        val key = key(contentHash, pageIndex, sizePx)
        val file = fileForKey(key)
        synchronized(lock) {
            ensureScanned()
            if (!file.exists() || file.length() == 0L) return null
            file.setLastModified(System.currentTimeMillis())
        }
        return reader(file)
    }

    /** Returns true if thumbnail exists in the cache. */
    fun contains(contentHash: String, pageIndex: Int, sizePx: Int): Boolean {
        val file = fileForKey(key(contentHash, pageIndex, sizePx))
        return synchronized(lock) {
            ensureScanned()
            file.exists() && file.length() > 0L
        }
    }

    /** Stores [bitmap] in the disk cache as WebP, evicting LRU entries if budget is exceeded. */
    fun put(contentHash: String, pageIndex: Int, sizePx: Int, bitmap: Bitmap): Boolean {
        val key = key(contentHash, pageIndex, sizePx)
        val file = fileForKey(key)
        synchronized(lock) { ensureScanned() }

        var temp: File? = null
        try {
            if (!directory.exists()) directory.mkdirs()
            // Unique per write, so concurrent writers of the same key never share a temp file.
            temp = File.createTempFile("$key.", TEMP_SUFFIX, directory)
            val written = writer(bitmap, temp)
            if (!written || !temp.exists() || temp.length() == 0L) {
                temp.delete()
                return false
            }

            synchronized(lock) {
                val oldSize = if (file.exists()) file.length() else 0L
                val newSize = temp.length()

                if (file.exists()) file.delete()
                if (!temp.renameTo(file)) {
                    temp.delete()
                    return false
                }
                file.setLastModified(System.currentTimeMillis())

                totalBytes += (newSize - oldSize)
                trimTo(maxBytes)
            }
            writeCount.update { it + 1 }
            return true
        } catch (e: IOException) {
            temp?.delete()
            return false
        }
    }

    /** Removes a thumbnail entry. Returns true if file was deleted. */
    fun remove(contentHash: String, pageIndex: Int, sizePx: Int): Boolean {
        val file = fileForKey(key(contentHash, pageIndex, sizePx))
        return synchronized(lock) {
            ensureScanned()
            if (file.exists()) {
                val len = file.length()
                if (file.delete()) {
                    totalBytes = maxOf(0L, totalBytes - len)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    /** Clears all thumbnails from the cache directory. In-flight writes are left alone. */
    fun clear() {
        synchronized(lock) {
            scanned = true
            cachedFiles().forEach { it.delete() }
            totalBytes = 0L
        }
    }

    /** Total bytes occupied by cached thumbnails in the directory. */
    fun sizeBytes(): Long = synchronized(lock) {
        ensureScanned()
        totalBytes
    }

    /** First use: counts cached bytes, deletes temp files a killed process left behind, and trims. */
    @GuardedBy("lock")
    private fun ensureScanned() {
        if (scanned) return
        scanned = true
        if (!directory.exists()) directory.mkdirs()
        directory.listFiles { file -> file.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
        totalBytes = cachedFiles().sumOf { it.length() }
        trimTo(maxBytes)
    }

    /** Finished thumbnails only; `*.tmp` files belong to writers that haven't counted them yet. */
    private fun cachedFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }?.toList().orEmpty()

    @GuardedBy("lock")
    private fun trimTo(targetBytes: Long) {
        if (totalBytes <= targetBytes) return
        val sorted = cachedFiles().sortedBy { it.lastModified() }
        for (file in sorted) {
            if (totalBytes <= targetBytes) break
            val len = file.length()
            if (file.delete()) {
                totalBytes = maxOf(0L, totalBytes - len)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024 // 200 MB per PRD §5
        const val DEFAULT_THUMBNAIL_WIDTH_PX = 400 // ~400 px wide per PRD §4.4
        private const val FILE_SUFFIX = ".webp"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
