package app.terbaca.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class ContentHashTest {

    private fun hashOf(bytes: ByteArray, maxReadPerCall: Int = Int.MAX_VALUE) =
        contentHash(bytes.size.toLong()) { buffer, offset ->
            val n = minOf(buffer.size, maxReadPerCall, (bytes.size - offset).toInt())
            bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + n)
            n
        }

    private val big = Random(42).nextBytes(300 * 1024)

    @Test
    fun sameContent_sameHash_evenWithShortReads() {
        assertEquals(hashOf(big), hashOf(big.copyOf(), maxReadPerCall = 1000))
    }

    @Test
    fun middleBytesDoNotAffectHash() {
        // By design (F-LIB-2) only size + first/last 64 KB are hashed.
        val edited = big.copyOf().also { it[150 * 1024] = (it[150 * 1024] + 1).toByte() }
        assertEquals(hashOf(big), hashOf(edited))
    }

    @Test
    fun headOrTailChange_changesHash() {
        val head = big.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val tail = big.copyOf().also { it[it.size - 10] = (it[it.size - 10] + 1).toByte() }
        assertNotEquals(hashOf(big), hashOf(head))
        assertNotEquals(hashOf(big), hashOf(tail))
    }

    @Test
    fun sizeChange_changesHash() {
        assertNotEquals(hashOf(big), hashOf(big.copyOf(big.size + 1)))
    }

    @Test
    fun smallAndEmptyFiles() {
        val small = byteArrayOf(1, 2, 3)
        assertEquals(64, hashOf(small).length)
        assertNotEquals(hashOf(small), hashOf(byteArrayOf()))
    }

    @Test(expected = IllegalStateException::class)
    fun truncatedRead_fails() {
        contentHash(100L) { _, _ -> 0 }
    }
}
