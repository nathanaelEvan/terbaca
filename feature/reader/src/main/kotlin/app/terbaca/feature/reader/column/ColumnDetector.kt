package app.terbaca.feature.reader.column

import app.terbaca.core.model.CharBox
import app.terbaca.core.model.ColumnRange
import app.terbaca.core.model.ColumnRegion
import app.terbaca.core.model.PageColumnLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Detects text columns and vertical regions on a document page (PRD §6.4, F-ACAD-1).
 *
 * Algorithm:
 * 1. Build a horizontal projection histogram of character boxes for the page to find
 *    near-empty vertical bands (gutters) between text columns.
 * 2. Segment the page vertically into regions (full-width vs. multi-column) to isolate
 *    titles, abstracts, and wide figures from multi-column body text.
 * 3. Output a [PageColumnLayout] with vertical regions and horizontal column bounds.
 */
object ColumnDetector {

    private const val BIN_SIZE = 2f
    private const val SLICE_HEIGHT = 10f
    private const val MIN_GUTTER_WIDTH = 10f
    private const val MIN_CHARS_FOR_DETECTION = 10
    private const val EDGE_MARGIN_POINTS = 60f

    /**
     * Detects column layout from the given [charBoxes] on page [pageIndex].
     */
    fun detect(
        pageIndex: Int,
        pageWidth: Float,
        pageHeight: Float,
        charBoxes: List<CharBox>
    ): PageColumnLayout {
        if (charBoxes.isEmpty()) {
            return PageColumnLayout(pageIndex, emptyList())
        }

        val contentLeft = charBoxes.minOf { it.left }
        val contentRight = charBoxes.maxOf { it.right }

        if (charBoxes.size < MIN_CHARS_FOR_DETECTION || (contentRight - contentLeft) < MIN_GUTTER_WIDTH * 3) {
            val singleRegion = ColumnRegion(
                top = 0f,
                bottom = pageHeight,
                columns = listOf(ColumnRange(contentLeft, contentRight))
            )
            return PageColumnLayout(pageIndex, listOf(singleRegion))
        }

        val gutters = findGutters(charBoxes, pageWidth, contentLeft, contentRight)
        if (gutters.isEmpty()) {
            val singleRegion = ColumnRegion(
                top = 0f,
                bottom = pageHeight,
                columns = listOf(ColumnRange(contentLeft, contentRight))
            )
            return PageColumnLayout(pageIndex, listOf(singleRegion))
        }

        val regions = segmentRegions(
            charBoxes = charBoxes,
            gutters = gutters,
            pageHeight = pageHeight,
            contentLeft = contentLeft,
            contentRight = contentRight
        )

        return PageColumnLayout(pageIndex, regions)
    }

    private data class Gutter(val left: Float, val right: Float)

    private fun findGutters(
        charBoxes: List<CharBox>,
        pageWidth: Float,
        contentLeft: Float,
        contentRight: Float
    ): List<Gutter> {
        val numBins = (pageWidth / BIN_SIZE).toInt()
        val hist = IntArray(numBins)

        for (box in charBoxes) {
            val b0 = max(0, (box.left / BIN_SIZE).toInt())
            val b1 = min(numBins - 1, (box.right / BIN_SIZE).toInt())
            for (b in b0..b1) {
                hist[b]++
            }
        }

        val bStart = ((contentLeft + EDGE_MARGIN_POINTS) / BIN_SIZE).toInt().coerceIn(0, numBins - 1)
        val bEnd = ((contentRight - EDGE_MARGIN_POINTS) / BIN_SIZE).toInt().coerceIn(0, numBins - 1)
        if (bStart >= bEnd) return emptyList()

        var minVal = Int.MAX_VALUE
        var maxVal = 0
        for (b in bStart..bEnd) {
            val v = hist[b]
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        // A gutter is a near-empty vertical band: density is significantly lower than column text
        val threshold = min(minVal + 2, (0.4f * maxVal).toInt())
        if (threshold >= (0.6f * maxVal).toInt()) return emptyList()

        val gutters = mutableListOf<Gutter>()
        var inGutter = false
        var gStart = 0

        for (b in bStart..bEnd) {
            if (hist[b] <= threshold) {
                if (!inGutter) {
                    inGutter = true
                    gStart = b
                }
            } else {
                if (inGutter) {
                    inGutter = false
                    val width = (b - gStart) * BIN_SIZE
                    if (width >= MIN_GUTTER_WIDTH) {
                        gutters.add(Gutter(gStart * BIN_SIZE, b * BIN_SIZE))
                    }
                }
            }
        }

        if (inGutter) {
            val width = (bEnd - gStart) * BIN_SIZE
            if (width >= MIN_GUTTER_WIDTH) {
                gutters.add(Gutter(gStart * BIN_SIZE, bEnd * BIN_SIZE))
            }
        }

        return gutters
    }

    private enum class SliceType { EMPTY, FULL_WIDTH, MULTI_COLUMN }

    private fun segmentRegions(
        charBoxes: List<CharBox>,
        gutters: List<Gutter>,
        pageHeight: Float,
        contentLeft: Float,
        contentRight: Float
    ): List<ColumnRegion> {
        val numSlices = (pageHeight / SLICE_HEIGHT).toInt() + 1
        val slices = Array(numSlices) { mutableListOf<CharBox>() }

        for (box in charBoxes) {
            val s0 = max(0, (box.top / SLICE_HEIGHT).toInt())
            val s1 = min(numSlices - 1, (box.bottom / SLICE_HEIGHT).toInt())
            for (s in s0..s1) {
                slices[s].add(box)
            }
        }

        val sliceTypes = Array(numSlices) { s ->
            val slice = slices[s]
            if (slice.isEmpty()) {
                SliceType.EMPTY
            } else {
                // Slices with characters crossing inside a gutter are full-width
                val crosses = slice.any { box ->
                    gutters.any { g ->
                        box.centerX > g.left + 1f && box.centerX < g.right - 1f
                    }
                }
                if (crosses) SliceType.FULL_WIDTH else SliceType.MULTI_COLUMN
            }
        }

        val sortedGutters = gutters.sortedBy { it.left }
        val standardColumns = mutableListOf<ColumnRange>()
        for (i in 0..sortedGutters.size) {
            val left = if (i == 0) contentLeft else sortedGutters[i - 1].right
            val right = if (i == sortedGutters.size) contentRight else sortedGutters[i].left
            if (left <= right) {
                standardColumns.add(ColumnRange(left, right))
            }
        }

        // Group contiguous non-empty slices into raw segments
        data class RawSegment(val type: SliceType, val startSlice: Int, val endSlice: Int)
        val rawSegments = mutableListOf<RawSegment>()
        var currentType: SliceType? = null
        var startSlice = 0
        var endSlice = 0

        for (s in 0 until numSlices) {
            val type = sliceTypes[s]
            if (type == SliceType.EMPTY) continue

            if (type != currentType) {
                if (currentType != null) {
                    rawSegments.add(RawSegment(currentType, startSlice, endSlice))
                }
                currentType = type
                startSlice = s
            }
            endSlice = s + 1
        }
        if (currentType != null) {
            rawSegments.add(RawSegment(currentType, startSlice, endSlice))
        }

        if (rawSegments.isEmpty()) {
            return listOf(
                ColumnRegion(0f, pageHeight, listOf(ColumnRange(contentLeft, contentRight)))
            )
        }

        val regions = mutableListOf<ColumnRegion>()
        for (i in rawSegments.indices) {
            val seg = rawSegments[i]
            val top = if (i == 0) 0f else (rawSegments[i - 1].endSlice + seg.startSlice) / 2f * SLICE_HEIGHT
            val bottom = if (i == rawSegments.lastIndex) {
                pageHeight
            } else {
                (seg.endSlice + rawSegments[i + 1].startSlice) / 2f * SLICE_HEIGHT
            }

            val segmentBoxes = charBoxes.filter { it.top < bottom && it.bottom > top }
            val columns = if (seg.type == SliceType.FULL_WIDTH) {
                val sLeft = segmentBoxes.minOfOrNull { it.left } ?: contentLeft
                val sRight = segmentBoxes.maxOfOrNull { it.right } ?: contentRight
                listOf(ColumnRange(sLeft, sRight))
            } else {
                standardColumns
            }

            regions.add(ColumnRegion(top, bottom, columns))
        }

        return regions
    }
}
