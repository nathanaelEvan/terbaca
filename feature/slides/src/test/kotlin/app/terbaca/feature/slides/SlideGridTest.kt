package app.terbaca.feature.slides

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SlideGridTest {
    @Test
    fun gridColumns_isThreeToFiveByWidth() {
        assertEquals(3, gridColumns(360.dp))
        assertEquals(3, gridColumns(700.dp))
        assertEquals(4, gridColumns(900.dp))
        assertEquals(5, gridColumns(1100.dp))
        assertEquals(5, gridColumns(2000.dp))
    }
}
