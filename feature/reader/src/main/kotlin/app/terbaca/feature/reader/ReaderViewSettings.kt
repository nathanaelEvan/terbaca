package app.terbaca.feature.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.terbaca.core.ui.chrome.LayoutOption
import app.terbaca.core.ui.chrome.LayoutOptions
import app.terbaca.core.ui.chrome.SettingsDivider
import app.terbaca.core.ui.chrome.SettingsToggle
import app.terbaca.core.ui.chrome.ThemeSwatch
import app.terbaca.core.ui.chrome.ThemeSwatches
import app.terbaca.core.ui.chrome.ViewSettingsPopover
import app.terbaca.core.ui.theme.TerbacaDarkBackground
import app.terbaca.core.ui.theme.TerbacaDarkTextPrimary

// The Sepia swatch: roughly what the page shader turns white paper and black ink into.
private val SEPIA_PAPER = Color(0xFFF4ECD8)
private val SEPIA_INK = Color(0xFF5B4636)

/**
 * The reader's "aA" popover: layout (scroll, two pages, slides), page colour (F-THEME-2) and the
 * options that go with them. [onSlides] is null when slide mode isn't offered.
 */
@Composable
internal fun ReaderViewSettings(
    spread: Boolean,
    canSpread: Boolean,
    onSpreadChange: (Boolean) -> Unit,
    coverAlone: Boolean,
    onCoverAloneChange: (Boolean) -> Unit,
    onSlides: (() -> Unit)?,
    pageTheme: PageTheme,
    onPageThemeChange: (PageTheme) -> Unit,
    invertImages: Boolean,
    onInvertImagesChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ViewSettingsPopover(title = stringResource(R.string.reader_view_settings), onDismiss = onDismiss) {
        LayoutOptions {
            LayoutOption(
                icon = Icons.Outlined.ViewDay,
                label = stringResource(R.string.reader_layout_vertical),
                selected = !spread,
                onClick = { onSpreadChange(false) }
            )
            if (canSpread) {
                LayoutOption(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    label = stringResource(R.string.reader_layout_two_pages),
                    selected = spread,
                    onClick = { onSpreadChange(true) }
                )
            }
            if (onSlides != null) {
                LayoutOption(
                    icon = Icons.Outlined.Slideshow,
                    label = stringResource(R.string.reader_layout_slides),
                    selected = false,
                    onClick = onSlides
                )
            }
        }
        if (spread) {
            SettingsToggle(
                icon = Icons.AutoMirrored.Outlined.Article,
                label = stringResource(R.string.reader_first_page_alone),
                checked = coverAlone,
                onCheckedChange = onCoverAloneChange
            )
        }
        SettingsDivider()
        ThemeSwatches(
            listOf(
                ThemeSwatch(
                    stringResource(R.string.reader_theme_day), Color.White, Color.Black,
                    pageTheme == PageTheme.ORIGINAL
                ) { onPageThemeChange(PageTheme.ORIGINAL) },
                ThemeSwatch(
                    stringResource(R.string.reader_theme_sepia), SEPIA_PAPER, SEPIA_INK,
                    pageTheme == PageTheme.SEPIA
                ) { onPageThemeChange(PageTheme.SEPIA) },
                ThemeSwatch(
                    stringResource(R.string.reader_theme_night), TerbacaDarkBackground, TerbacaDarkTextPrimary,
                    pageTheme == PageTheme.DARK
                ) { onPageThemeChange(PageTheme.DARK) }
            )
        )
        if (pageTheme != PageTheme.ORIGINAL) {
            SettingsToggle(
                icon = Icons.Outlined.Image,
                label = stringResource(R.string.reader_theme_images),
                checked = invertImages,
                onCheckedChange = onInvertImagesChange
            )
        }
    }
}
