package app.terbaca

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.terbaca.core.ui.chrome.FloatingPill
import app.terbaca.core.ui.chrome.LayoutOption
import app.terbaca.core.ui.chrome.LayoutOptions
import app.terbaca.core.ui.chrome.SettingsToggle
import app.terbaca.core.ui.chrome.ToolbarIconButton
import app.terbaca.core.ui.chrome.ToolbarTextSettingsButton
import app.terbaca.core.ui.chrome.TopToolbar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M5-04: the shared chrome is readable by TalkBack, its touch targets are at least 48 dp, and it
 * still lays out at the largest font size. Lives in :app because CI runs :app's instrumented tests.
 */
@RunWith(AndroidJUnit4::class)
class ChromeAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    /** Renders [content] with the system font size at [fontScale] (2.0 is Android's largest). */
    private fun setChrome(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                content()
            }
        }
    }

    @Test
    fun toolbar_atLargestFont_keepsTitleAsHeadingAndButtonsAt48dp() {
        setChrome(fontScale = 2f) {
            TopToolbar(
                visible = true,
                title = "Attention Is All You Need.pdf",
                trailing = {
                    ToolbarIconButton(icon = Icons.Rounded.Search, contentDescription = "Search", onClick = {})
                    ToolbarTextSettingsButton(contentDescription = "View Settings", onClick = {}, selected = false)
                }
            )
        }

        compose.onNodeWithText("Attention Is All You Need.pdf").assertIsDisplayed().assert(isHeading())
        compose.onNodeWithContentDescription("Search")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        // TalkBack reads "View Settings", not the "aA" glyph drawn on the button.
        compose.onNodeWithContentDescription("View Settings")
            .assertWidthIsAtLeast(48.dp)
            .assert(!hasText("aA"))
    }

    @Test
    fun tappablePill_reservesA48dpTouchTarget() {
        var clicks = 0
        setChrome {
            FloatingPill(modifier = Modifier.testTag("pill"), onClick = { clicks++ }, onClickLabel = "Go to page") {
                Text("3 / 30")
            }
        }

        // The pill draws ~32 dp tall; minimumInteractiveComponentSize widens its touch area to 48 dp.
        compose.onNodeWithTag("pill").assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithText("3 / 30").performClick()
        compose.runOnIdle { check(clicks == 1) { "pill tap should call onClick once, got $clicks" } }
    }

    @Test
    fun pageIndicatorPill_labelAndTapAreOneTalkBackStop() {
        // Same shape as the reader's page indicator: the label goes on the pill, the visible text is hidden.
        setChrome {
            FloatingPill(
                modifier = Modifier.semantics { contentDescription = "Page 3 of 30" },
                onClick = {},
                onClickLabel = "Go to page"
            ) {
                Text("3 / 30", modifier = Modifier.clearAndSetSemantics { })
            }
        }

        compose.onNodeWithContentDescription("Page 3 of 30")
            .assert(hasClickAction())
            .assert(SemanticsMatcher("click label is \"Go to page\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Go to page" })
            .assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun settingsToggle_isASwitchThatReportsItsState() {
        setChrome {
            var on by remember { mutableStateOf(false) }
            SettingsToggle(icon = Icons.Rounded.ViewDay, label = "First Page Alone", checked = on, onCheckedChange = { on = it })
        }

        val toggle = compose.onNodeWithText("First Page Alone")
        toggle.assertIsOff().assertHeightIsAtLeast(48.dp)
        toggle.performClick()
        toggle.assertIsOn()
    }

    @Test
    fun layoutOption_isASelectableRadioButton() {
        setChrome {
            LayoutOptions {
                LayoutOption(icon = Icons.Rounded.ViewDay, label = "Vertical Scroll", selected = true, onClick = {})
            }
        }

        compose.onNodeWithText("Vertical Scroll")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }
}
