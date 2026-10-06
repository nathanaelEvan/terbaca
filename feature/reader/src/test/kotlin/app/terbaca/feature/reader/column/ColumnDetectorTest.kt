package app.terbaca.feature.reader.column

import app.terbaca.core.model.CharBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColumnDetectorTest {

    private val boxRegex = Regex(
        """"left":\s*([\d.-]+),\s*"top":\s*([\d.-]+),\s*"right":\s*([\d.-]+),\s*"bottom":\s*([\d.-]+)"""
    )

    private fun loadFixture(fixtureName: String): List<CharBox> {
        val stream = javaClass.getResourceAsStream("/fixtures/columns/$fixtureName")
            ?: error("Fixture not found: $fixtureName")
        val json = stream.bufferedReader().use { it.readText() }
        return boxRegex.findAll(json).map { match ->
            val (l, t, r, b) = match.destructured
            CharBox(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        }.toList()
    }

    @Test
    fun detect_emptyBoxes_returnsEmptyLayout() {
        val layout = ColumnDetector.detect(0, 600f, 800f, emptyList())
        assertEquals(0, layout.pageIndex)
        assertTrue(layout.regions.isEmpty())
        assertNull(layout.findColumn(100f, 100f))
    }

    @Test
    fun detect_sparseBoxes_returnsSingleFullWidthRegion() {
        val boxes = listOf(
            CharBox(100f, 100f, 110f, 112f),
            CharBox(110f, 100f, 120f, 112f)
        )
        val layout = ColumnDetector.detect(0, 600f, 800f, boxes)
        assertEquals(1, layout.regions.size)
        assertFalse(layout.regions[0].isMultiColumn)
        // Full-width region returns null for findColumn (fit-page width)
        assertNull(layout.findColumn(100f, 100f))
    }

    @Test
    fun detect_syntheticTwoColumn_detectsGutterAndColumns() {
        val boxes = mutableListOf<CharBox>()
        // Generate two columns:
        // Col 1: left 50..250, lines from y=100 to y=700 every 15pt
        // Col 2: left 300..500, lines from y=100 to y=700 every 15pt
        for (y in 100..700 step 15) {
            // Col 1 characters
            for (x in 50..240 step 8) {
                boxes.add(CharBox(x.toFloat(), y.toFloat(), (x + 6).toFloat(), (y + 10).toFloat()))
            }
            // Col 2 characters
            for (x in 300..490 step 8) {
                boxes.add(CharBox(x.toFloat(), y.toFloat(), (x + 6).toFloat(), (y + 10).toFloat()))
            }
        }

        val layout = ColumnDetector.detect(0, 600f, 800f, boxes)
        assertTrue(layout.regions.isNotEmpty())

        val multiColRegion = layout.regions.firstOrNull { it.isMultiColumn }
        assertNotNull("Expected at least one multi-column region", multiColRegion)
        assertEquals(2, multiColRegion!!.columns.size)

        val col1 = multiColRegion.columns[0]
        val col2 = multiColRegion.columns[1]

        assertTrue("Col 1 left should be near 50", col1.left in 45f..55f)
        assertTrue("Col 1 right should be near 246", col1.right in 240f..255f)
        assertTrue("Col 2 left should be near 300", col2.left in 295f..305f)
        assertTrue("Col 2 right should be near 496", col2.right in 490f..505f)

        // Column double-tap zoom resolution
        val tappedCol1 = layout.findColumn(150f, 400f)
        assertNotNull(tappedCol1)
        assertEquals(col1.left, tappedCol1!!.left, 0.01f)

        val tappedCol2 = layout.findColumn(400f, 400f)
        assertNotNull(tappedCol2)
        assertEquals(col2.left, tappedCol2!!.left, 0.01f)
    }

    @Test
    fun detect_syntheticMixedPage_segmentsFullWidthTitleAndTwoColumnBody() {
        val boxes = mutableListOf<CharBox>()
        // Full-width title at y=50..80 spanning 50..500
        for (y in 50..80 step 15) {
            for (x in 50..490 step 8) {
                boxes.add(CharBox(x.toFloat(), y.toFloat(), (x + 6).toFloat(), (y + 10).toFloat()))
            }
        }
        // Two-column body at y=150..700
        for (y in 150..700 step 15) {
            for (x in 50..240 step 8) {
                boxes.add(CharBox(x.toFloat(), y.toFloat(), (x + 6).toFloat(), (y + 10).toFloat()))
            }
            for (x in 300..490 step 8) {
                boxes.add(CharBox(x.toFloat(), y.toFloat(), (x + 6).toFloat(), (y + 10).toFloat()))
            }
        }

        val layout = ColumnDetector.detect(0, 600f, 800f, boxes)
        assertTrue("Layout should have at least 2 regions", layout.regions.size >= 2)

        // Tap in title area (y=60) -> full-width, returns null
        assertNull("Tap in title must return null for full-width zoom", layout.findColumn(200f, 60f))

        // Tap in body col1 (y=300) -> returns col1
        val bodyCol1 = layout.findColumn(100f, 300f)
        assertNotNull("Tap in column 1 must resolve column", bodyCol1)
        assertTrue(bodyCol1!!.left in 45f..55f)

        // Tap in body col2 (y=300) -> returns col2
        val bodyCol2 = layout.findColumn(400f, 300f)
        assertNotNull("Tap in column 2 must resolve column", bodyCol2)
        assertTrue(bodyCol2!!.left in 295f..305f)
    }

    @Test
    fun detect_corpusFixture_paperTwoColumnPage0_detectsTitleAbstractAndBody() {
        val boxes = loadFixture("paper_two_column_page0.json")
        assertTrue(boxes.isNotEmpty())

        val layout = ColumnDetector.detect(0, 595.28f, 841.89f, boxes)

        // Check that layout contains multi-column body region
        val bodyRegion = layout.regions.firstOrNull { it.isMultiColumn }
        assertNotNull("Expected multi-column region on two-column page 0", bodyRegion)
        assertEquals(2, bodyRegion!!.columns.size)

        // Tap inside Column 1 (x=100, y=300)
        val col1 = layout.findColumn(100f, 300f)
        assertNotNull(col1)
        assertTrue("Col 1 left should be ~54", col1!!.left in 50f..60f)
        assertTrue("Col 1 right should be ~287", col1.right in 270f..300f)

        // Tap inside Column 2 (x=400, y=300)
        val col2 = layout.findColumn(400f, 300f)
        assertNotNull(col2)
        assertTrue("Col 2 left should be ~310", col2!!.left in 305f..320f)
        assertTrue("Col 2 right should be ~553", col2.right in 535f..580f)

        // Tap inside Abstract block (x=200, y=130) -> should be full-width (returns null)
        assertNull("Tap inside Abstract must resolve to null (full-width)", layout.findColumn(200f, 130f))
    }

    @Test
    fun detect_corpusFixture_paperTwoColumnPage1_detectsTwoColumns() {
        val boxes = loadFixture("paper_two_column_page1.json")
        assertTrue(boxes.isNotEmpty())

        val layout = ColumnDetector.detect(1, 595.28f, 841.89f, boxes)

        val multiColRegion = layout.regions.firstOrNull { it.isMultiColumn }
        assertNotNull("Expected multi-column region on two-column page 1", multiColRegion)
        assertEquals(2, multiColRegion!!.columns.size)

        val col1 = layout.findColumn(100f, 400f)
        assertNotNull(col1)
        assertTrue("Col 1 left should be ~54", col1!!.left in 50f..60f)
        assertTrue("Col 1 right should be ~287", col1.right in 270f..300f)

        val col2 = layout.findColumn(400f, 400f)
        assertNotNull(col2)
        assertTrue("Col 2 left should be ~310", col2!!.left in 305f..320f)
        assertTrue("Col 2 right should be ~553", col2.right in 535f..560f)
    }

    @Test
    fun detect_corpusFixture_paperSingleColumnPage0_detectsSingleFullWidth() {
        val boxes = loadFixture("paper_single_column_page0.json")
        assertTrue(boxes.isNotEmpty())

        val layout = ColumnDetector.detect(0, 612f, 792f, boxes)

        // Must not detect multi-column regions
        val multiColRegion = layout.regions.firstOrNull { it.isMultiColumn }
        assertNull("Single column paper should not have multi-column regions", multiColRegion)

        // Any tap returns null (signals full-page width zoom)
        assertNull(layout.findColumn(200f, 200f))
        assertNull(layout.findColumn(100f, 400f))
    }
}
