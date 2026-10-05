package app.terbaca.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingModeTest {

    @Test
    fun emptyPagesDefaultToContinuousVertical() {
        assertEquals(ReadingMode.CONTINUOUS_VERTICAL, autoDetectReadingMode(emptyList()))
    }

    @Test
    fun portraitFirstPageDetectsContinuousVertical() {
        val pages = listOf(
            PageInfo(0, widthPoints = 612f, heightPoints = 792f), // US Letter portrait
            PageInfo(1, widthPoints = 1920f, heightPoints = 1080f)
        )
        assertEquals(ReadingMode.CONTINUOUS_VERTICAL, autoDetectReadingMode(pages))
    }

    @Test
    fun landscapeFirstPageDetectsSlideMode() {
        val pages = listOf(
            PageInfo(0, widthPoints = 960f, heightPoints = 540f), // 16:9 landscape
            PageInfo(1, widthPoints = 612f, heightPoints = 792f)
        )
        assertEquals(ReadingMode.SLIDE, autoDetectReadingMode(pages))
    }

    @Test
    fun squareFirstPageDetectsContinuousVertical() {
        val pages = listOf(
            PageInfo(0, widthPoints = 600f, heightPoints = 600f)
        )
        assertEquals(ReadingMode.CONTINUOUS_VERTICAL, autoDetectReadingMode(pages))
    }
}
