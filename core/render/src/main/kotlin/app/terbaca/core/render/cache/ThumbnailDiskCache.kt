package app.terbaca.core.render.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.GuardedBy
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
 * - Thread-safe and atomic writes via temp file renaming.
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

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        synchronized(lock) {
            totalBytes = directory.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
            trimTo(maxBytes)
        }
    }

    fun key(contentHash: String, pageIndex: Int, sizePx: Int): String =
        "${contentHash}_${pageIndex}_$sizePx"

    private fun fileForKey(key: String): File = File(directory, "$key.webp")
    private fun tempFileForKey(key: String): File = File(directory, "$key.tmp")

    /** Returns cached thumbnail bitmap, updating its access time for LRU tracking. */
    fun get(contentHash: String, pageIndex: Int, sizePx: Int): Bitmap? {
        val key = key(contentHash, pageIndex, sizePx)
        val file = fileForKey(key)
        synchronized(lock) {
            if (!file.exists() || file.length() == 0L) return null
            file.setLastModified(System.currentTimeMillis())
        }
        return reader(file)
    }

    /** Returns true if thumbnail exists in the cache. */
    fun contains(contentHash: String, pageIndex: Int, sizePx: Int): Boolean {
        val file = fileForKey(key(contentHash, pageIndex, sizePx))
        return synchronized(lock) { file.exists() && file.length() > 0L }
    }

    /** Stores [bitmap] in the disk cache as WebP, evicting LRU entries if budget is exceeded. */
    fun put(contentHash: String, pageIndex: Int, sizePx: Int, bitmap: Bitmap): Boolean {
        val key = key(contentHash, pageIndex, sizePx)
        val file = fileForKey(key)
        val temp = tempFileForKey(key)

        try {
            if (!directory.exists()) directory.mkdirs()
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
            return true
        } catch (e: IOException) {
            temp.delete()
            return false
        }
    }

    /** Removes a thumbnail entry. Returns true if file was deleted. */
    fun remove(contentHash: String, pageIndex: Int, sizePx: Int): Boolean {
        val file = fileForKey(key(contentHash, pageIndex, sizePx))
        return synchronized(lock) {
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

    /** Clears all thumbnails from the cache directory. */
    fun clear() {
        synchronized(lock) {
            directory.listFiles()?.forEach { it.delete() }
            totalBytes = 0L
        }
    }

    /** Total bytes occupied by cached files in the directory. */
    fun sizeBytes(): Long = synchronized(lock) { totalBytes }

    @GuardedBy("lock")
    private fun trimTo(targetBytes: Long) {
        if (totalBytes <= targetBytes) return
        val files = directory.listFiles() ?: return
        val sorted = files.filter { it.isFile }.sortedBy { it.lastModified() }
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
    }
}
