@file:OptIn(ExperimentalComposeUiApi::class)

package app.call2remind.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.call2remind.R
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.CircleIconButton
import app.call2remind.ui.components.LampDot
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.pressScale
import app.call2remind.ui.format.timeParts
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics
import java.time.Duration
import java.time.Instant

/** Snooze choices (minutes). The fourth chip is "Custom". */
internal val SNOOZE_PRESETS = listOf(5L, 10L, 15L)
private const val CUSTOM_STEP = 5L
private const val CUSTOM_MIN = 5L
private const val CUSTOM_MAX = 240L
private const val CUSTOM_DEFAULT = 30L
private const val CUSTOM_INDEX = 3

/**
 * Content of the snooze bottom sheet ("Ring again in"). The container (slide-up spring, drag to
 * dismiss) is [CallScreen]'s; this is the part that is also used standalone in tests.
 *
 * When [CallPresentation.canSnooze] is false (no ring-backs left on an unanswered call) the
 * choices are hidden and the sheet explains that declining moves the reminder to Missed.
 *
 * @param answered answered calls are exempt from the ring-back limit, so the lamps row is hidden.
 */
@Composable
fun SnoozeSheetContent(
    presentation: CallPresentation,
    answered: Boolean,
    now: Instant,
    onSnooze: (Duration) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val haptics = rememberHaptics()
    val defaultMinutes = presentation.defaultSnooze.toMinutes()
    var selected by rememberSaveable { mutableIntStateOf(SNOOZE_PRESETS.indexOf(defaultMinutes).takeIf { it >= 0 } ?: 0) }
    var customMinutes by rememberSaveable { mutableIntStateOf(CUSTOM_DEFAULT.toInt()) }
    val minutes = if (selected == CUSTOM_INDEX) customMinutes.toLong() else SNOOZE_PRESETS[selected]

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(C2RTheme.shapes.pill)
                .background(c.line),
        )
        if (!presentation.canSnooze) {
            Text(
                stringResource(R.string.snooze_none_title),
                style = C2RTheme.type.sheetTitle,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.snooze_none_body), style = C2RTheme.type.body, color = c.muted)
            PillButton(stringResource(R.string.snooze_back_to_call), onBack, Modifier.fillMaxWidth(), style = PillStyle.Primary, height = 60.dp)
            return@Column
        }

        Text(
            stringResource(R.string.snooze_title),
            style = C2RTheme.type.sheetTitle,
            modifier = Modifier.semantics { heading() },
        )

        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in 0..1) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (col in 0..1) {
                        val index = row * 2 + col
                        val isCustom = index == CUSTOM_INDEX
                        val chipMinutes = if (isCustom) customMinutes.toLong() else SNOOZE_PRESETS[index]
                        SnoozeChip(
                            value = if (isCustom) stringResource(R.string.snooze_custom) else stringResource(R.string.snooze_minutes, chipMinutes),
                            detail = if (isCustom && selected != CUSTOM_INDEX) {
                                stringResource(R.string.snooze_pick)
                            } else {
                                timeParts(now.plus(Duration.ofMinutes(chipMinutes))).text
                            },
                            selected = selected == index,
                            onClick = {
                                if (selected != index) haptics.perform(Haptic.SegmentTick)
                                selected = index
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(CallTags.snoozeChip(index)),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selected == CUSTOM_INDEX,
            enter = expandVertically(C2RTheme.motion.precise()) + fadeIn(C2RTheme.motion.fade()),
            exit = shrinkVertically(C2RTheme.motion.precise()) + fadeOut(C2RTheme.motion.fade()),
        ) {
            CustomStepper(
                minutes = customMinutes,
                onChange = {
                    haptics.perform(Haptic.SegmentTick)
                    customMinutes = it
                },
            )
        }

        if (!answered && presentation.maxRingBacks > 0) {
            RingBacksNote(presentation)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PillButton(
                text = stringResource(R.string.snooze_confirm, minutes),
                onClick = {
                    haptics.perform(Haptic.Confirm)
                    onSnooze(Duration.ofMinutes(minutes))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CallTags.SNOOZE_CONFIRM),
                style = PillStyle.Primary,
                height = 60.dp,
            )
            PillButton(stringResource(R.string.snooze_back_to_call), onBack, Modifier.fillMaxWidth(), style = PillStyle.Ghost, height = 48.dp)
        }
    }
}

@Composable
private fun SnoozeChip(value: String, detail: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(if (selected) c.ink else c.ground, motion.fade(), label = "chipBg")
    val content by animateColorAsState(if (selected) c.surface else c.ink, motion.fade(), label = "chipFg")
    val sub by animateColorAsState(if (selected) c.surface.copy(alpha = 0.75f) else c.muted, motion.fade(), label = "chipSub")
    Column(
        modifier = modifier
            .pressScale(interaction)
            .heightIn(min = 76.dp)
            .clip(C2RTheme.shapes.chip)
            .background(container)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(value, style = C2RTheme.type.chipValue, color = content)
        Text(detail, style = C2RTheme.type.caption, color = sub)
    }
}

@Composable
private fun CustomStepper(minutes: Int, onChange: (Int) -> Unit) {
    val c = C2RTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(C2RTheme.shapes.box)
            .background(c.ground)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            C2RIcons.Minus,
            stringResource(R.string.snooze_less),
            onClick = { onChange((minutes - CUSTOM_STEP.toInt()).coerceAtLeast(CUSTOM_MIN.toInt())) },
            enabled = minutes > CUSTOM_MIN,
        )
        Text(
            stringResource(R.string.snooze_minutes, minutes.toLong()),
            style = C2RTheme.type.monoTimer.copy(fontWeight = FontWeight.Bold),
            color = c.ink,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        CircleIconButton(
            C2RIcons.Plus,
            stringResource(R.string.snooze_more),
            onClick = { onChange((minutes + CUSTOM_STEP.toInt()).coerceAtMost(CUSTOM_MAX.toInt())) },
            enabled = minutes < CUSTOM_MAX,
        )
    }
}

/**
 * "2 of 3 ring-backs left": used ring-backs are dark lamps; the lamp this snooze would use
 * breathes (dims and recovers) so the cost of snoozing is visible before committing.
 */
@Composable
private fun RingBacksNote(presentation: CallPresentation) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val left = presentation.ringBacksLeft
    val max = presentation.maxRingBacks
    val summary = pluralStringResource(R.plurals.snooze_ringbacks_left, left, left, max)
    val detail = stringResource(R.string.snooze_ringbacks_then_missed)
    val breathe = if (!motion.reduced) {
        rememberInfiniteTransition(label = "ringBack").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(BREATHE_MS), RepeatMode.Reverse),
            label = "ringBackAlpha",
        )
    } else {
        null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(C2RTheme.shapes.box)
            .background(c.ground)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clearAndSetSemantics { contentDescription = "$summary $detail" },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val used = max - left
            for (i in 0 until max) {
                val isNext = i == used
                LampDot(
                    lit = i >= used,
                    size = 14.dp,
                    modifier = if (isNext && breathe != null) Modifier.graphicsLayer { alpha = breathe.value } else Modifier,
                )
            }
        }
        Text(
            text = buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(summary)
                pop()
                append(" ")
                pushStyle(SpanStyle(color = c.muted))
                append(detail)
                pop()
            },
            style = C2RTheme.type.caption,
            color = c.ink,
        )
    }
}

private const val BREATHE_MS = 900
