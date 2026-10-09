package app.terbaca.feature.reader

import android.graphics.RuntimeShader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Compiles the AGSL page theme shader on a device and sets every uniform it declares (F-THEME-3). */
@RunWith(AndroidJUnit4::class)
class PageThemeShaderTest {

    private val rects = ThemeRects(FloatArray(MAX_THEMED_PAGES * 4), 1, FloatArray(MAX_MASKED_IMAGES * 4), 1)

    @Test
    fun shaderCompiles_andBuildsEffectForEachTheme() {
        val shader = RuntimeShader(PAGE_THEME_AGSL)
        assertNotNull(pageThemeEffect(shader, PageTheme.DARK, invertImages = false, rects))
        assertNotNull(pageThemeEffect(shader, PageTheme.SEPIA, invertImages = true, rects))
        assertNull(pageThemeEffect(shader, PageTheme.ORIGINAL, invertImages = false, rects))
    }
}
