package app.call2remind.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics

/**
 * The exchange switch (52×32). Off: outlined track, 16 dp muted thumb. On: ink track, 22 dp lamp
 * thumb. The thumb travels on the playful spring, so it overshoots and settles, and grows as it
 * lights. Purely visual; put the toggle semantics on the row ([ToggleRow]).
 */
@Composable
fun LampSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val position by animateFloatAsState(if (checked) 1f else 0f, motion.playful(0.001f), label = "switchPos")
    val thumb by animateDpAsState(if (checked) 22.dp else 16.dp, motion.playful(), label = "switchThumb")
    val track by animateColorAsState(if (checked) c.ink else c.ink.copy(alpha = 0f), motion.fade(), label = "switchTrack")
    val thumbColor by animateColorAsState(if (checked) c.lamp else c.muted, motion.fade(), label = "switchThumbColor")
    val outline = c.muted
    Canvas(modifier.size(width = 52.dp, height = 32.dp)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(track, cornerRadius = radius)
        if (!checked || track.alpha < 1f) {
            val w = 2.dp.toPx()
            drawRoundRect(
                color = outline.copy(alpha = 1f - track.alpha),
                topLeft = Offset(w / 2f, w / 2f),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(size.height / 2f - w / 2f),
                style = Stroke(w),
            )
        }
        val r = thumb.toPx() / 2f
        // Off: thumb centre 7+8 = 15 dp from the left; on: 22+11 = 33 dp (from the mockup).
        val start = 15.dp.toPx()
        val end = 33.dp.toPx()
        drawCircle(thumbColor, radius = r, center = Offset(start + (end - start) * position, size.height / 2f))
    }
}

/**
 * A settings row with a [LampSwitch]. The whole row is the touch target (≥ 48 dp) and carries
 * Switch semantics for TalkBack; toggling ticks.
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = {
                    haptics.perform(Haptic.SegmentTick)
                    onCheckedChange(it)
                },
            )
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = C2RTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium))
            if (subtitle != null) {
                Text(subtitle, style = C2RTheme.type.caption, color = C2RTheme.colors.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        LampSwitch(checked, Modifier.pressScale(interaction, pressedScale = 0.94f))
    }
}

/** Section title above a grouped card ("Ringing", "Snooze and missed"). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = C2RTheme.type.section,
        color = C2RTheme.colors.ink,
        modifier = modifier
            .semantics { heading() }
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
    )
}
