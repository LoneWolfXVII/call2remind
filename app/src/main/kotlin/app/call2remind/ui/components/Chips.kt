package app.call2remind.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics

/**
 * A round day-of-week toggle (44 dp). Turning on, the ink fill blooms from the centre on the
 * playful spring (overshooting the rim a hair) and the letter flips to the surface colour; turning
 * off, the fill drains back to the outline. Drawn, not clipped, so the whole circle is the target.
 */
@Composable
fun DayToggle(
    letter: String,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val fill by animateFloatAsState(if (checked) 1f else 0f, motion.playful(0.001f), label = "dayFill")
    val text by animateColorAsState(if (checked) c.surface else c.ink, motion.fade(), label = "dayText")
    val ink = c.ink
    Box(
        modifier
            .pressScale(interaction, pressedScale = 0.9f)
            .size(44.dp)
            .drawBehind {
                val r = size.minDimension / 2f
                val stroke = 1.5.dp.toPx()
                drawCircle(ink, radius = r - stroke / 2f, style = Stroke(stroke))
                if (fill > 0f) drawCircle(ink, radius = r * fill.coerceAtMost(1.06f))
            }
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                role = Role.Checkbox,
                onValueChange = {
                    haptics.perform(Haptic.SegmentTick)
                    onCheckedChange(it)
                },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = C2RTheme.type.section, color = text)
    }
}

/**
 * A rounded choice chip ("Medicine", "All", "Missed only"). Selected: ink fill; otherwise a
 * surface chip with a hairline. Colours cross-fade; the chip squeezes on press.
 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    role: Role = Role.RadioButton,
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(if (selected) c.ink else c.surface, motion.fade(), label = "chipBg")
    val content by animateColorAsState(if (selected) c.surface else c.ink, motion.fade(), label = "chipFg")
    val outline by animateColorAsState(if (selected) c.ink else c.line, motion.fade(), label = "chipLine")
    val shape = C2RTheme.shapes.tileSmall
    Row(
        modifier
            .pressScale(interaction)
            .height(40.dp)
            .background(container, shape)
            .border(1.dp, outline, shape)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = role, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(text, style = C2RTheme.type.button.copy(fontSize = C2RTheme.type.section.fontSize), color = content, maxLines = 1)
    }
}

/** A small tag ("Best effort"): lamp-tinted fill, dark text. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    Text(
        text,
        style = C2RTheme.type.navLabel.copy(fontWeight = FontWeight.Bold),
        color = c.onLamp,
        modifier = modifier
            .background(c.lamp.copy(alpha = if (c.isDark) 0.85f else 0.32f), C2RTheme.shapes.tileSmall)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Test tags of the undo bar. */
object UndoBarTags {
    const val BAR = "undo_bar"
    const val UNDO = "undo_bar_undo"
}

/**
 * The undo bar after a swipe: "[message]  Undo" on ink, with a lamp hairline that drains over
 * [durationMs]. The drain is the timer: when it empties, [onTimeout] commits the action. A new
 * [key] restarts it.
 */
@Composable
fun UndoBar(
    key: Any,
    message: String,
    actionLabel: String,
    onUndo: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Int = UNDO_WINDOW_MS,
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val remaining = remember(key) { Animatable(1f) }
    val timeout by rememberUpdatedState(onTimeout)
    LaunchedEffect(key) {
        // The timer is a plain delay: with animations turned off the drain jumps, but the user
        // still gets the full window to undo.
        launch { remaining.animateTo(0f, tween(durationMs, easing = LinearEasing)) }
        delay(durationMs.toLong())
        timeout()
    }
    val lamp = c.lamp
    val shape = C2RTheme.shapes.box
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .background(c.ink, shape)
            .drawBehind {
                val h = 3.dp.toPx()
                val inset = 16.dp.toPx()
                val w = (size.width - inset * 2) * remaining.value
                drawRoundRect(lamp, Offset(inset, size.height - h - 6.dp.toPx()), Size(w, h), CornerRadius(h / 2f))
            }
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(UndoBarTags.BAR)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = message,
            transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.fade()) },
            modifier = Modifier.weight(1f),
            label = "undoMessage",
        ) { text ->
            Text(text, style = C2RTheme.type.body, color = c.surface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PillButton(
            actionLabel,
            onClick = onUndo,
            style = PillStyle.Lamp,
            height = 40.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            textStyle = C2RTheme.type.button.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.testTag(UndoBarTags.UNDO),
        )
    }
}

/** How long the undo bar waits before committing. */
const val UNDO_WINDOW_MS: Int = 4_000
