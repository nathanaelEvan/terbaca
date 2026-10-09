package app.terbaca.core.ui.sidebar

import app.terbaca.core.model.OutlineItem
import org.junit.Assert.assertEquals
import org.junit.Test

class OutlineFlattenTest {
    @Test
    fun flattenOutline_listsChildrenAfterParentWithDepth() {
        val outline = listOf(
            OutlineItem("1 Intro", 0, children = listOf(OutlineItem("1.1 Scope", 1))),
            OutlineItem("2 Method", 3)
        )
        val rows = flattenOutline(outline)
        assertEquals(listOf("1 Intro", "1.1 Scope", "2 Method"), rows.map { it.item.title })
        assertEquals(listOf(0, 1, 0), rows.map { it.depth })
    }
}
