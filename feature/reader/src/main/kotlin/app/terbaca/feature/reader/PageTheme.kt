package app.terbaca.feature.reader

import android.content.Context
import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import app.terbaca.core.model.PageRect

/** Page themes (F-THEME-2). Applied at draw time by [PAGE_THEME_AGSL], so switching needs no re-render. */
enum class PageTheme {
    ORIGINAL,
    DARK,
    SEPIA;

    fun next(): PageTheme = entries[(ordinal + 1) % entries.size]
}

/** Visible pages the shader transforms; the gaps between them keep the app background. */
internal const val MAX_THEMED_PAGES = 8

/** Image rectangles left untransformed (F-THEME-3); beyond this count images are transformed too. */
internal const val MAX_MASKED_IMAGES = 16

private const val PREFS = "terbaca_reader"
private const val KEY_THEME = "page_theme"

/** The page theme last chosen, remembered across documents (F-THEME-2). */
internal fun loadPageTheme(context: Context): PageTheme =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)
        ?.let { name -> PageTheme.entries.firstOrNull { it.name == name } }
        ?: PageTheme.ORIGINAL

internal fun savePageTheme(context: Context, theme: PageTheme) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, theme.name).apply()
}

/**
 * Screen-space rectangles for the shader, as packed (left, top, right, bottom) floats: the visible
 * [pages] (up to [MAX_THEMED_PAGES]) and their raster images from [imageBounds] (up to
 * [MAX_MASKED_IMAGES]). Returns the two arrays and how many entries of each are used.
 */
internal fun themeRects(
    state: DocumentTransformState,
    pages: IntRange,
    imageBounds: Map<Int, List<PageRect>>
): ThemeRects {
    val pageRects = FloatArray(MAX_THEMED_PAGES * 4)
    val imageRects = FloatArray(MAX_MASKED_IMAGES * 4)
    var pageCount = 0
    var imageCount = 0
    val s = state.scale
    for (index in pages) {
        if (pageCount >= MAX_THEMED_PAGES) break
        val page = state.layout.pageRect(index)
        put(pageRects, pageCount++, page.left * s + state.offsetX, page.top * s + state.offsetY,
            page.right * s + state.offsetX, page.bottom * s + state.offsetY)
        for (r in imageBounds[index].orEmpty()) {
            if (imageCount >= MAX_MASKED_IMAGES) break
            put(imageRects, imageCount++, (page.left + r.left) * s + state.offsetX, (page.top + r.top) * s + state.offsetY,
                (page.left + r.right) * s + state.offsetX, (page.top + r.bottom) * s + state.offsetY)
        }
    }
    return ThemeRects(pageRects, pageCount, imageRects, imageCount)
}

private fun put(array: FloatArray, slot: Int, left: Float, top: Float, right: Float, bottom: Float) {
    array[slot * 4] = left
    array[slot * 4 + 1] = top
    array[slot * 4 + 2] = right
    array[slot * 4 + 3] = bottom
}

internal class ThemeRects(val pages: FloatArray, val pageCount: Int, val images: FloatArray, val imageCount: Int)

/** Builds the render effect for [theme] over [rects]; null for [PageTheme.ORIGINAL]. */
internal fun pageThemeEffect(
    shader: RuntimeShader,
    theme: PageTheme,
    invertImages: Boolean,
    rects: ThemeRects
): RenderEffect? {
    if (theme == PageTheme.ORIGINAL) return null
    shader.setFloatUniform("mode", if (theme == PageTheme.DARK) 1f else 2f)
    shader.setFloatUniform("invertImages", if (invertImages) 1f else 0f)
    shader.setFloatUniform("pageCount", rects.pageCount.toFloat())
    shader.setFloatUniform("pages", rects.pages)
    shader.setFloatUniform("imageCount", rects.imageCount.toFloat())
    shader.setFloatUniform("images", rects.images)
    return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
}

/**
 * Page theme shader (PRD §6.5, F-THEME-3/4). Inside the visible pages and outside raster images, it
 * converts the colour to OKLab and remaps lightness with a tone curve while keeping hue and chroma
 * (a, b), so black text turns off-white and coloured plot lines keep their colour:
 * - Dark: white paper → ~#121214, black text → ~#E4E4E7.
 * - Sepia: white paper → warm cream, black text → dark brown.
 */
internal const val PAGE_THEME_AGSL = """
uniform shader content;
uniform float mode;
uniform float invertImages;
uniform float pageCount;
uniform float4 pages[8];
uniform float imageCount;
uniform float4 images[16];

float3 toLinear(float3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, float3(2.4)), step(0.04045, c));
}

float3 toSrgb(float3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, float3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

float3 linearToOklab(float3 c) {
    float l = pow(max(0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b, 0.0), 1.0 / 3.0);
    float m = pow(max(0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b, 0.0), 1.0 / 3.0);
    float s = pow(max(0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b, 0.0), 1.0 / 3.0);
    return float3(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
}

float3 oklabToLinear(float3 c) {
    float l = c.x + 0.3963377774 * c.y + 0.2158037573 * c.z;
    float m = c.x - 0.1055613458 * c.y - 0.0638541728 * c.z;
    float s = c.x - 0.0894841775 * c.y - 1.2914855480 * c.z;
    l = l * l * l;
    m = m * m * m;
    s = s * s * s;
    return float3(
        4.0767416621 * l - 3.3077382600 * m + 0.2309424468 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

bool inside(float2 p, float4 r) {
    return p.x >= r.x && p.y >= r.y && p.x <= r.z && p.y <= r.w;
}

half4 main(float2 xy) {
    half4 c = content.eval(xy);
    bool onPage = false;
    for (int i = 0; i < 8; i++) {
        if (float(i) < pageCount && inside(xy, pages[i])) onPage = true;
    }
    if (!onPage || c.a <= 0.0) return c;
    if (invertImages < 0.5) {
        for (int i = 0; i < 16; i++) {
            if (float(i) < imageCount && inside(xy, images[i])) return c;
        }
    }
    float3 lab = linearToOklab(toLinear(float3(c.rgb) / float(c.a)));
    if (mode < 1.5) {
        // Dark: L 1 (paper) -> 0.183 (#121214), L 0 (ink) -> 0.917 (#E4E4E7).
        lab.x = 0.917 - (0.917 - 0.183) * lab.x;
    } else {
        // Sepia: paper -> warm cream, ink -> dark brown, both tinted towards yellow-red.
        float t = lab.x;
        lab.x = 0.26 + (0.93 - 0.26) * t;
        lab.y += mix(0.02, 0.006, t);
        lab.z += mix(0.03, 0.035, t);
    }
    float3 rgb = toSrgb(oklabToLinear(lab));
    return half4(half3(rgb) * c.a, c.a);
}
"""
