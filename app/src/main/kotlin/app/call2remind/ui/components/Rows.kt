package app.call2remind.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics

/**
 * A grouped card of rows on the surface colour (Settings, Sources, Detail). Rows draw their own
 * hairline dividers ([rowDivider]); the last row passes `divider = false`.
 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(C2RTheme.colors.surface, C2RTheme.shapes.card)
            .padding(horizontal = 16.dp),
        content = content,
    )
}

/** A section: header + [GroupCard] + optional footnote under it. */
@Composable
fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(horizontal = 16.dp)) {
        if (title != null) SectionHeader(title)
        GroupCard(content = content)
        if (footnote != null) {
            Text(
                footnote,
                style = C2RTheme.type.footnote,
                color = C2RTheme.colors.muted,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            )
        }
    }
}

/** Hairline under a row inside a [GroupCard]. */
fun Modifier.rowDivider(show: Boolean, color: Color): Modifier =
    if (!show) {
        this
    } else {
        drawBehind {
            val y = size.height - 0.5.dp.toPx()
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
    }

/**
 * A tappable row: optional leading icon, title + value line, trailing chevron. Presses squeeze the
 * text block a touch (no ripple, like the pills).
 */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: ImageVector? = null,
    divider: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = C2RTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .rowDivider(divider, c.line)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .pressScale(interaction, pressedScale = 0.985f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = C2RTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium), color = c.ink)
            if (value != null) {
                Text(value, style = C2RTheme.type.caption, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(C2RIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp))
        }
    }
}

/** A non-interactive row: title + explanation (behaviour the app handles for you). */
@Composable
fun InfoRow(title: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null, divider: Boolean = true) {
    val c = C2RTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .rowDivider(divider, c.line)
            .defaultMinSize(minHeight = 64.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = C2RTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium), color = c.ink)
            Text(value, style = C2RTheme.type.caption, color = c.muted)
        }
    }
}

/**
 * A segmented choice ("30 s | 45 s | 60 s"). The ink indicator slides between segments on the
 * playful spring and stretches while travelling, the same lamp-along-the-rail move as the tab bar.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val haptics = rememberHaptics()
    val position = remember { Animatable(selectedIndex.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) position.animateTo(selectedIndex.toFloat(), motion.playful(0.001f))
    }
    val ink = c.ink
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier
            .fillMaxWidth()
            .background(c.ground, C2RTheme.shapes.box)
            .padding(4.dp)
            .selectableGroup()
            .drawBehind {
                if (options.isEmpty() || selectedIndex < 0) return@drawBehind
                val w = size.width / options.size
                val travel = kotlin.math.abs(position.value - position.targetValue).coerceAtMost(1f)
                val stretch = w * 0.18f * travel
                val fromStart = w * position.value - stretch / 2f
                // Segments run right-to-left in RTL; the pill follows.
                val left = if (rtl) size.width - fromStart - (w + stretch) else fromStart
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(left.coerceAtLeast(0f), 0f),
                    size = Size((w + stretch).coerceAtMost(size.width), size.height),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
            },
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selected,
                        interactionSource = null,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = {
                            if (!selected) {
                                haptics.perform(Haptic.SegmentTick)
                                onSelect(index)
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = C2RTheme.type.button.copy(fontSize = C2RTheme.type.section.fontSize),
                    color = if (selected) c.surface else c.ink,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** − value + stepper ("Ring-backs before Missed"). The number rolls when it changes. */
@Composable
fun Stepper(
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    decreaseLabel: String,
    increaseLabel: String,
    valueDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val haptics = rememberHaptics()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(
            C2RIcons.Minus,
            decreaseLabel,
            onClick = {
                haptics.perform(Haptic.SegmentTick)
                onChange(value - 1)
            },
            enabled = value > range.first,
        )
        Box(
            Modifier
                .width(28.dp)
                .clearAndSetSemantics {
                    contentDescription = valueDescription
                    stateDescription = value.toString()
                },
            contentAlignment = Alignment.Center,
        ) {
            RollingText(value.toString(), style = C2RTheme.type.monoTimer.copy(fontSize = C2RTheme.type.chipValue.fontSize), color = c.ink)
        }
        CircleIconButton(
            C2RIcons.Plus,
            increaseLabel,
            onClick = {
                haptics.perform(Haptic.SegmentTick)
                onChange(value + 1)
            },
            enabled = value < range.last,
        )
    }
}

/** A labelled block inside a card ("Voice says" / quote). */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, divider: Boolean = true) {
    val c = C2RTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .rowDivider(divider, c.line)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = C2RTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
        Text(value, style = C2RTheme.type.body.copy(lineHeight = C2RTheme.type.rowTitle.lineHeight), color = c.ink)
    }
}
