package app.call2remind.ui.ringtone

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.R
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.CircleIconButton
import app.call2remind.ui.components.LampDot
import app.call2remind.ui.components.LampPulse
import app.call2remind.ui.components.TopBar
import app.call2remind.ui.components.labelRes
import app.call2remind.ui.components.pressTint
import app.call2remind.ui.components.rowDivider
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics

/** Test tags for the ringtone picker. */
object RingtoneTags {
    const val DEFAULT = "ringtone_default"

    fun row(uri: String) = "ringtone_row_$uri"

    fun play(uri: String?) = "ringtone_play_${uri ?: DEFAULT_TONE_KEY}"
}

@Immutable
data class RingtoneActions(
    val onBack: () -> Unit = {},
    val onSelect: (String?) -> Unit = {},
    val onPreview: (String?) -> Unit = {},
)

@Composable
fun RingtoneRoute(
    onBack: () -> Unit,
    onHabitResult: (String?) -> Unit,
    viewModel: RingtoneViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPreview() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopPreview() }
    }
    RingtoneScreen(
        state = state,
        actions = RingtoneActions(
            onBack = onBack,
            onSelect = { uri ->
                viewModel.select(uri)
                if (viewModel.target == RingtoneTarget.Habit) onHabitResult(uri)
            },
            onPreview = viewModel::togglePreview,
        ),
    )
}

/**
 * The ringtone picker (the Ringtone mockup): a radio list of our tones, then the phone's own
 * sounds. The radio dot springs in; the play button turns into a lit, pulsing lamp while that
 * sound previews.
 */
@Composable
fun RingtoneScreen(state: RingtoneUiState, actions: RingtoneActions, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopBar(onBack = actions.onBack)
        Text(
            stringResource(R.string.ringtone_title),
            style = C2RTheme.type.screenTitle,
            color = c.ink,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .semantics { heading() },
        )
        Text(
            when (val t = state.target) {
                RingtoneTarget.Default -> stringResource(R.string.ringtone_for_default)
                is RingtoneTarget.Source -> stringResource(R.string.ringtone_for_source, stringResource(t.type.labelRes))
                RingtoneTarget.Habit -> stringResource(R.string.ringtone_for_habit)
            },
            style = C2RTheme.type.body,
            color = c.muted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item(key = "default") {
                val title = when (state.target) {
                    RingtoneTarget.Default -> stringResource(R.string.tone_default_alarm)
                    is RingtoneTarget.Source -> stringResource(R.string.ringtone_same_as_default)
                    RingtoneTarget.Habit -> stringResource(R.string.habit_ringtone_default)
                }
                CardTop {
                    ToneRow(
                        title = title,
                        description = stringResource(R.string.ringtone_default_desc),
                        uri = null,
                        selected = state.selected == null,
                        playing = state.playing == DEFAULT_TONE_KEY,
                        actions = actions,
                        divider = true,
                        tag = RingtoneTags.DEFAULT,
                    )
                }
            }
            toneItems("b", state.bundled, state, actions, last = true)
            item(key = "system-header") {
                Row(
                    Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.ringtone_on_phone), style = C2RTheme.type.section, color = c.ink, modifier = Modifier.semantics { heading() })
                    if (state.system == null) LampDot(lit = true, pulse = true, size = 8.dp)
                }
            }
            val system = state.system.orEmpty()
            if (state.system != null && system.isEmpty()) {
                item(key = "system-empty") {
                    Text(stringResource(R.string.ringtone_none_on_phone), style = C2RTheme.type.body, color = c.muted, modifier = Modifier.padding(4.dp))
                }
            }
            toneItems("s", system, state, actions, first = true, last = true)
        }
    }
}

private fun LazyListScope.toneItems(
    prefix: String,
    tones: List<Tone>,
    state: RingtoneUiState,
    actions: RingtoneActions,
    first: Boolean = false,
    last: Boolean = false,
) {
    itemsIndexed(tones, key = { _, tone -> "$prefix-${tone.uri}" }) { index, tone ->
        val isFirst = first && index == 0
        val isLast = last && index == tones.lastIndex
        CardSlice(isFirst, isLast) {
            ToneRow(
                title = tone.title,
                description = tone.description,
                uri = tone.uri,
                selected = state.selected == tone.uri,
                playing = state.playing == tone.uri,
                actions = actions,
                divider = !isLast,
                tag = RingtoneTags.row(tone.uri),
            )
        }
    }
}

/** A slice of a card spread over several lazy items: rounded only where the card starts / ends. */
@Composable
private fun CardSlice(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val r = 20.dp
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp,
        topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp,
        bottomEnd = if (last) r else 0.dp,
    )
    // Drawn, not clipped: unequal corners on a clip would break hit testing in tests.
    Box(
        Modifier
            .fillMaxWidth()
            .background(C2RTheme.colors.surface, shape)
            .padding(horizontal = 16.dp),
    ) { content() }
}

@Composable
private fun CardTop(content: @Composable () -> Unit) = CardSlice(true, false, content)

@Composable
private fun ToneRow(
    title: String,
    description: String?,
    uri: String?,
    selected: Boolean,
    playing: Boolean,
    actions: RingtoneActions,
    divider: Boolean,
    tag: String,
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val dot by animateFloatAsState(if (selected) 1f else 0f, motion.playful(0.001f), label = "radioDot")
    val ink = c.ink
    Row(
        Modifier
            .fillMaxWidth()
            .rowDivider(divider, c.line),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .pressTint(interaction, c.tint.copy(alpha = 0.6f))
                .selectable(
                    selected = selected,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = {
                        if (!selected) haptics.perform(Haptic.SegmentTick)
                        actions.onSelect(uri)
                    },
                )
                .testTag(tag)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = 2.dp.toPx()
                drawCircle(ink, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
                if (dot > 0f) drawCircle(ink, radius = 5.5.dp.toPx() * dot)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = C2RTheme.type.rowTitle.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium), color = c.ink)
                if (description != null) Text(description, style = C2RTheme.type.caption, color = c.muted)
            }
        }
        PlayButton(uri, title, playing, actions.onPreview)
    }
}

/** Play / stop. While previewing, the button is a lit lamp with a slow pulse around it. */
@Composable
private fun PlayButton(uri: String?, title: String, playing: Boolean, onPreview: (String?) -> Unit) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val bg by animateColorAsState(if (playing) c.lamp else c.ground, motion.fade(), label = "playBg")
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (playing && !motion.reduced) LampPulse(color = c.lamp, dotSize = 40.dp, maxScale = 1.35f)
        Box(Modifier.size(40.dp).background(bg, C2RTheme.shapes.pill))
        CircleIconButton(
            icon = if (playing) C2RIcons.Pause else C2RIcons.Play,
            contentDescription = stringResource(if (playing) R.string.ringtone_stop else R.string.ringtone_play, title),
            onClick = { onPreview(uri) },
            tint = if (playing) c.onLamp else c.ink,
            modifier = Modifier.testTag(RingtoneTags.play(uri)),
        )
    }
}
