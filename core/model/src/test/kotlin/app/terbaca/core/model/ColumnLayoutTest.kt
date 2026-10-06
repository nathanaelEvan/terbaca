package app.terbaca.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColumnLayoutTest {

    @Test
    fun columnRange_propertiesAndContains() {
        val col = ColumnRange(50f, 250f)
        assertEquals(200f, col.width, 0.001f)
        assertEquals(150f, col.centerX, 0.001f)
        assertTrue(col.contains(50f))
        assertTrue(col.contains(150f))
        assertTrue(col.contains(250f))
        assertFalse(col.contains(49.9f))
        assertFalse(col.contains(250.1f))
    }

    @Test
    fun columnRegion_multiColumnDetection() {
        val fullWidth = ColumnRegion(0f, 100f, listOf(ColumnRange(50f, 550f)))
        assertFalse(fullWidth.isMultiColumn)
        assertEquals(100f, fullWidth.height, 0.001f)
        assertTrue(fullWidth.containsY(50f))

        val twoCol = ColumnRegion(
            100f,
            800f,
            listOf(ColumnRange(50f, 280f), ColumnRange(320f, 550f))
        )
        assertTrue(twoCol.isMultiColumn)
        assertEquals(700f, twoCol.height, 0.001f)
    }

    @Test
    fun pageColumnLayout_findColumn_multiColumnMatchesCorrectColumn() {
        val layout = PageColumnLayout(
            pageIndex = 0,
            regions = listOf(
                ColumnRegion(0f, 150f, listOf(ColumnRange(50f, 550f))), // Title/Abstract
                ColumnRegion(
                    150f,
                    800f,
                    listOf(ColumnRange(50f, 280f), ColumnRange(320f, 550f)) // Two columns
                )
            )
        )

        // Inside title region -> null (full-page width fallback)
        assertNull(layout.findColumn(100f, 100f))
        assertNull(layout.findColumn(400f, 50f))

        // Inside column 1
        val col1 = layout.findColumn(150f, 300f)
        assertNotNull(col1)
        assertEquals(50f, col1!!.left, 0.001f)
        assertEquals(280f, col1.right, 0.001f)

        // Inside column 2
        val col2 = layout.findColumn(400f, 300f)
        assertNotNull(col2)
        assertEquals(320f, col2!!.left, 0.001f)
        assertEquals(550f, col2.right, 0.001f)

        // Tapping in the gutter chooses closest column
        val gutterLeft = layout.findColumn(285f, 300f)
        assertEquals(50f, gutterLeft!!.left, 0.001f)

        val gutterRight = layout.findColumn(315f, 300f)
        assertEquals(320f, gutterRight!!.left, 0.001f)

        // Tapping outside regions returns null
        assertNull(layout.findColumn(100f, 900f))
    }
}
