@file:OptIn(ExperimentalFoundationApi::class)

package app.call2remind.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.call2remind.ui.theme.C2RTheme

/** Visual variants from the mockups' button styles. */
enum class PillStyle {
    /** Ink fill, surface text: the main action on light grounds ("Continue"). */
    Primary,

    /** Lamp fill, ink text: the one hot action on a panel ("Done", "Answer"). */
    Lamp,

    /** 1.5 dp ink outline ("Cancel test"). */
    Outline,

    /** Text only ("Skip, finish setup", "Back to call"). */
    Ghost,

    /** 1.5 dp outline at 45 % on the panel ("Snooze" on the call screen). */
    OnPanelOutline,

    /** Text only on the panel ("Read it again"). */
    OnPanelGhost,

    /** Missed-red fill, surface text: the one destructive confirmation ("Delete habit"). */
    Danger,

    /** Missed-red text only: a destructive action that asks for confirmation first. */
    DangerGhost,
}

@Immutable
private data class PillColors(val container: Color, val content: Color, val outline: Color?)

@Composable
private fun PillStyle.colors(): PillColors {
    val c = C2RTheme.colors
    return when (this) {
        PillStyle.Primary -> PillColors(c.ink, c.surface, null)
        PillStyle.Lamp -> PillColors(c.lamp, c.onLamp, null)
        PillStyle.Outline -> PillColors(Color.Transparent, c.ink, c.ink)
        PillStyle.Ghost -> PillColors(Color.Transparent, c.ink, null)
        PillStyle.OnPanelOutline -> PillColors(Color.Transparent, c.onPanel, c.onPanelOutline)
        PillStyle.OnPanelGhost -> PillColors(Color.Transparent, c.onPanel, null)
        PillStyle.Danger -> PillColors(c.missed, c.surface, null)
        PillStyle.DangerGhost -> PillColors(Color.Transparent, c.missed, null)
    }
}

/**
 * The pill button. Squeezes to 0.97 on press with a spring (no ripple: the flat exchange look
 * uses physical press feedback instead). Always at least 48 dp tall.
 *
 * @param onLongClick optional long press ("Hold for 10 min or a custom time").
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    icon: ImageVector? = null,
    height: Dp = 56.dp,
    enabled: Boolean = true,
    textStyle: TextStyle = C2RTheme.type.button,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val colors = style.colors()
    val interaction = remember { MutableInteractionSource() }
    val shape = C2RTheme.shapes.pill
    Row(
        modifier = modifier
            .pressScale(interaction)
            .defaultMinSize(minHeight = 48.dp)
            .height(height)
            .clip(shape)
            .background(colors.container, shape)
            .then(if (colors.outline != null) Modifier.border(1.5.dp, colors.outline, shape) else Modifier)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(20.dp))
        }
        Text(text, style = textStyle, color = colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A 48 dp circular icon button (back arrow, steppers). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = C2RTheme.colors.ink,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(interaction, pressedScale = 0.9f)
            .size(48.dp)
            .clip(C2RTheme.shapes.pill)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
    }
}

private const val DISABLED_ALPHA = 0.4f
