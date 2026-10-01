package app.terbaca.core.pdfium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class CorpusIntegrityTest {

    private val expectedFiles = listOf(
        "paper_two_column.pdf",
        "paper_single_column.pdf",
        "heavy_plot.pdf",
        "scanned_doc.pdf",
        "deck_16_9.pdf",
        "deck_4_3.pdf",
        "memory_100plus.pdf",
        "malformed_truncated.pdf",
        "malformed_corrupt.pdf",
        "encrypted_password.pdf",
    )

    private fun findCorpusDir(): File {
        var current: File? = File(".").canonicalFile
        while (current != null) {
            val candidate = File(current, "testdata/corpus")
            if (candidate.exists() && candidate.isDirectory) {
                return candidate
            }
            current = current.parentFile
        }
        error("Could not find testdata/corpus directory from ${File(".").canonicalPath}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testAllCorpusFilesExistAndAreNonEmpty() {
        val corpusDir = findCorpusDir()
        for (filename in expectedFiles) {
            val file = File(corpusDir, filename)
            assertTrue("Expected corpus file $filename to exist", file.exists())
            assertTrue("Expected corpus file $filename to be non-empty", file.length() > 0)
        }
    }

    @Test
    fun testValidPdfFilesHavePdfHeader() {
        val corpusDir = findCorpusDir()
        val validFiles = expectedFiles.filter { !it.startsWith("malformed_corrupt") }
        for (filename in validFiles) {
            val file = File(corpusDir, filename)
            val header = ByteArray(5)
            file.inputStream().use { it.read(header) }
            assertEquals("File $filename must start with %PDF-", "%PDF-", String(header))
        }
    }

    @Test
    fun testSourcesManifestMatchesChecksums() {
        val corpusDir = findCorpusDir()
        val sourcesFile = File(corpusDir, "SOURCES.md")
        assertTrue("SOURCES.md must exist in corpus directory", sourcesFile.exists())
        val sourcesText = sourcesFile.readText()

        for (filename in expectedFiles) {
            val file = File(corpusDir, filename)
            val actualHash = sha256(file)

            assertTrue("SOURCES.md must mention $filename", sourcesText.contains(filename))
            assertTrue(
                "SOURCES.md must contain correct SHA-256 for $filename ($actualHash)",
                sourcesText.contains(actualHash),
            )
        }
    }
}
