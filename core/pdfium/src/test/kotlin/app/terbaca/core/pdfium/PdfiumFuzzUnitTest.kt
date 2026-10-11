package app.terbaca.core.pdfium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * JVM unit test suite verifying robustness of error resolution, exception mapping,
 * and outline tree parsing against malformed and random inputs.
 */
class PdfiumFuzzUnitTest {

    @Test
    fun testErrorCodeFuzzing_neverThrowsUnexpectedExceptions() {
        val rng = Random(1234)
        for (i in 0 until 500) {
            val code = rng.nextInt()
            val ex = PdfiumException.fromErrorCode(code, "Fuzzed error $code")
            assertNotNull(ex)
            assertEquals("Fuzzed error $code", ex.message)
            assertEquals(code, ex.errorCode)
        }
    }

    @Test
    fun testOutlineTreeBuilder_fuzzedAndMalformedOutlineParcels() {
        // Deeply nested / corrupted depths
        val parcels = listOf(
            OutlineItemParcel().apply { title = "Title 1"; pageIndex = 0; depth = 0 },
            OutlineItemParcel().apply { title = "Title 2"; pageIndex = 0; depth = 5 },   // Skip depth 0 -> 5
            OutlineItemParcel().apply { title = "Title 3"; pageIndex = 0; depth = -2 },  // Negative depth
            OutlineItemParcel().apply { title = "Title 4"; pageIndex = -1; depth = 100 }, // Huge depth, negative page
            OutlineItemParcel().apply { title = "Title 5"; pageIndex = 9999; depth = 1 }
        )

        val tree = buildOutlineTree(parcels)
        assertNotNull(tree)
        assertTrue(tree.isNotEmpty())
    }

    @Test
    fun testOutlineTreeBuilder_emptyAndSingleItem() {
        val emptyTree = buildOutlineTree(emptyList())
        assertTrue(emptyTree.isEmpty())

        val single = buildOutlineTree(listOf(OutlineItemParcel().apply { title = "Root"; pageIndex = 0; depth = 0 }))
        assertEquals(1, single.size)
        assertEquals("Root", single[0].title)
        assertTrue(single[0].children.isEmpty())
    }
}
