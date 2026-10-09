package app.terbaca.feature.slides

import org.junit.Assert.assertEquals
import org.junit.Test

class SlideTwoUpTest {
    @Test
    fun twoUpPageCount_roundsUp() {
        assertEquals(0, twoUpPageCount(0))
        assertEquals(1, twoUpPageCount(1))
        assertEquals(2, twoUpPageCount(4))
        assertEquals(3, twoUpPageCount(5))
    }

    @Test
    fun twoUpSlides_pairsAndLastAlone() {
        assertEquals(0..1, twoUpSlides(0, 5))
        assertEquals(2..3, twoUpSlides(1, 5))
        assertEquals(4..4, twoUpSlides(2, 5))
    }
}
