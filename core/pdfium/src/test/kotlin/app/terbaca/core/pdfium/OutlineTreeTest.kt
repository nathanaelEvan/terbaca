package app.terbaca.core.pdfium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTreeTest {

    @Test
    fun buildOutlineTree_emptyList_returnsEmpty() {
        val result = buildOutlineTree(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun buildOutlineTree_flatList_returnsAllRoots() {
        val parcels = listOf(
            OutlineItemParcel().apply {
                title = "Section 1"
                pageIndex = 0
                destX = 10f
                destY = 20f
                depth = 0
            },
            OutlineItemParcel().apply {
                title = "Section 2"
                pageIndex = 5
                destX = Float.NaN
                destY = Float.NaN
                depth = 0
            }
        )

        val tree = buildOutlineTree(parcels)
        assertEquals(2, tree.size)

        assertEquals("Section 1", tree[0].title)
        assertEquals(0, tree[0].pageIndex)
        assertEquals(10f, tree[0].destX ?: 0f, 0.001f)
        assertEquals(20f, tree[0].destY ?: 0f, 0.001f)
        assertTrue(tree[0].children.isEmpty())

        assertEquals("Section 2", tree[1].title)
        assertEquals(5, tree[1].pageIndex)
        assertNull(tree[1].destX)
        assertNull(tree[1].destY)
        assertTrue(tree[1].children.isEmpty())
    }

    @Test
    fun buildOutlineTree_nestedHierarchy_buildsCorrectTree() {
        val parcels = listOf(
            OutlineItemParcel().apply { title = "1. Intro"; pageIndex = 0; depth = 0 },
            OutlineItemParcel().apply { title = "1.1 Motivation"; pageIndex = 1; depth = 1 },
            OutlineItemParcel().apply { title = "1.2 Contribution"; pageIndex = 2; depth = 1 },
            OutlineItemParcel().apply { title = "1.2.1 Specifics"; pageIndex = 3; depth = 2 },
            OutlineItemParcel().apply { title = "2. Related Work"; pageIndex = 4; depth = 0 },
            OutlineItemParcel().apply { title = "3. Conclusion"; pageIndex = 10; depth = 0 }
        )

        val tree = buildOutlineTree(parcels)
        assertEquals(3, tree.size)

        val intro = tree[0]
        assertEquals("1. Intro", intro.title)
        assertEquals(2, intro.children.size)

        assertEquals("1.1 Motivation", intro.children[0].title)
        assertTrue(intro.children[0].children.isEmpty())

        val contribution = intro.children[1]
        assertEquals("1.2 Contribution", contribution.title)
        assertEquals(1, contribution.children.size)
        assertEquals("1.2.1 Specifics", contribution.children[0].title)

        assertEquals("2. Related Work", tree[1].title)
        assertTrue(tree[1].children.isEmpty())

        assertEquals("3. Conclusion", tree[2].title)
        assertTrue(tree[2].children.isEmpty())
    }
}
