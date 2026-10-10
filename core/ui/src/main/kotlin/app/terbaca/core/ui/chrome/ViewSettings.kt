package app.terbaca.core.ui.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.terbaca.core.ui.theme.TerbacaDarkAccent
import app.terbaca.core.ui.theme.TerbacaDarkBorder
import app.terbaca.core.ui.theme.TerbacaDarkSurfaceHigh
import app.terbaca.core.ui.theme.TerbacaDarkSurfaceVariant
import app.terbaca.core.ui.theme.TerbacaDarkTextPrimary
import app.terbaca.core.ui.theme.TerbacaDarkTextSecondary

private val PopoverWidth = 320.dp
private val PopoverMargin = 8.dp

/**
 * The "View Settings" popover, hanging from the top-right of the toolbar (the "aA" button).
 * A tap outside or Back dismisses it.
 */
@Composable
fun ViewSettingsPopover(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density) + with(density) { (ToolbarHeight + 4.dp).roundToPx() }
    val end = with(density) { PopoverMargin.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(-end, top),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier.width(PopoverWidth),
            shape = RoundedCornerShape(14.dp),
            color = TerbacaDarkSurfaceVariant,
            border = BorderStroke(0.5.dp, TerbacaDarkBorder),
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    color = TerbacaDarkTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                content()
            }
        }
    }
}

/** A hairline between popover sections. */
@Composable
fun SettingsDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(0.5.dp)
            .background(TerbacaDarkBorder)
    )
}

/** A row of [LayoutOption]s: scroll, slides, two pages. */
@Composable
fun LayoutOptions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        content = content
    )
}

/** One reading layout: an outlined tile with an icon, a label below, ringed when [selected]. */
@Composable
fun RowScope.LayoutOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(TerbacaDarkSurfaceHigh)
                .border(
                    if (selected) 2.dp else 0.5.dp,
                    if (selected) TerbacaDarkAccent else TerbacaDarkBorder,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) TerbacaDarkAccent else TerbacaDarkTextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = if (selected) TerbacaDarkAccent else TerbacaDarkTextPrimary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

/** A page colour choice: a circle showing "Aa" in that theme's colours. */
class ThemeSwatch(
    val label: String,
    val background: Color,
    val text: Color,
    val selected: Boolean,
    val onClick: () -> Unit
)

/** The Day / Sepia / Night circles. */
@Composable
fun ThemeSwatches(swatches: List<ThemeSwatch>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        for (swatch in swatches) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .semantics { selected = swatch.selected }
                    .clickable(role = Role.RadioButton, onClick = swatch.onClick)
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .border(2.dp, if (swatch.selected) TerbacaDarkAccent else Color.Transparent, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(swatch.background)
                        .border(0.5.dp, TerbacaDarkBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Aa", color = swatch.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = swatch.label,
                    color = if (swatch.selected) TerbacaDarkAccent else TerbacaDarkTextPrimary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/** An icon, a label and a switch, like the iOS settings rows. */
@Composable
fun SettingsToggle(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TerbacaDarkAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = TerbacaDarkTextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = TerbacaDarkAccent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = TerbacaDarkSurfaceHigh,
                uncheckedThumbColor = TerbacaDarkTextSecondary,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
