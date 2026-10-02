package app.terbaca.core.data

import android.os.ParcelFileDescriptor
import android.system.Os
import java.security.MessageDigest

/** Bytes hashed from each end of the file (F-LIB-2). */
const val CONTENT_HASH_CHUNK_BYTES = 64 * 1024

/**
 * F-LIB-2: identifies a document by content rather than URI, so its recents entry and state
 * survive the file being reopened from another location: SHA-256 of the file size plus the
 * first and last 64 KB. Reads are positional (F-OPEN-3) and don't move the descriptor offset.
 */
fun computeContentHash(pfd: ParcelFileDescriptor): String {
    val fd = pfd.fileDescriptor
    val size = Os.fstat(fd).st_size
    return contentHash(size) { buffer, offset -> Os.pread(fd, buffer, 0, buffer.size, offset) }
}

/**
 * [readAt] fills the buffer from the given file offset and returns the bytes read (may be short).
 */
internal fun contentHash(size: Long, readAt: (ByteArray, Long) -> Int): String {
    require(size >= 0) { "Negative file size: $size" }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(longToBytes(size))

    val headLength = minOf(size, CONTENT_HASH_CHUNK_BYTES.toLong()).toInt()
    digest.update(readFully(headLength, 0L, readAt))

    val tailLength = minOf(size, CONTENT_HASH_CHUNK_BYTES.toLong()).toInt()
    digest.update(readFully(tailLength, size - tailLength, readAt))

    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun readFully(length: Int, offset: Long, readAt: (ByteArray, Long) -> Int): ByteArray {
    val out = ByteArray(length)
    var done = 0
    while (done < length) {
        val chunk = ByteArray(length - done)
        val read = readAt(chunk, offset + done)
        check(read > 0) { "Unexpected end of file at ${offset + done}" }
        chunk.copyInto(out, destinationOffset = done, endIndex = read)
        done += read
    }
    return out
}

private fun longToBytes(value: Long) = ByteArray(Long.SIZE_BYTES) { i ->
    (value ushr (8 * (Long.SIZE_BYTES - 1 - i))).toByte()
}
