@file:OptIn(ExperimentalLayoutApi::class)

package app.call2remind.ui.habit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.R
import app.call2remind.core.speech.SpeechText
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.C2RSheet
import app.call2remind.ui.components.ChoiceChip
import app.call2remind.ui.components.CircleIconButton
import app.call2remind.ui.components.DayToggle
import app.call2remind.ui.components.GroupCard
import app.call2remind.ui.components.NavRow
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.Stepper
import app.call2remind.ui.components.TimePickerSheetContent
import app.call2remind.ui.components.ToggleRow
import app.call2remind.ui.components.pressScale
import app.call2remind.ui.components.rowDivider
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.format.currentLocale
import app.call2remind.ui.format.localTimeText
import app.call2remind.ui.format.localeWeek
import app.call2remind.ui.format.weekdayFull
import app.call2remind.ui.format.weekdayLetter
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics
import java.time.DayOfWeek
import java.time.LocalTime

/** Test tags for the habit editor. */
object HabitTags {
    const val TITLE = "habit_title"
    const val SAVE = "habit_save"
    const val ADD_TIME = "habit_add_time"
    const val DELETE = "habit_delete"
    const val DELETE_CONFIRM = "habit_delete_confirm"
    const val ERROR_TITLE = "habit_error_title"
    const val ERROR_DAYS = "habit_error_days"
    const val ERROR_TIMES = "habit_error_times"

    fun day(day: DayOfWeek) = "habit_day_${day.name}"

    fun time(index: Int) = "habit_time_$index"

    fun template(template: HabitTemplate) = "habit_template_${template.name}"
}

@Immutable
data class HabitEditorActions(
    val onClose: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onTitle: (String) -> Unit = {},
    val onToggleDay: (DayOfWeek) -> Unit = {},
    val onAddTime: (LocalTime) -> Unit = {},
    val onReplaceTime: (LocalTime, LocalTime) -> Unit = { _, _ -> },
    val onRemoveTime: (LocalTime) -> Unit = {},
    val onInterval: (Int) -> Unit = {},
    val onTts: (Boolean) -> Unit = {},
    val onNotes: (String) -> Unit = {},
    val onTemplate: (HabitTemplate, String) -> Unit = { _, _ -> },
    val onPickRingtone: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

@Composable
fun HabitEditorRoute(
    ringtoneResult: String?,
    onRingtoneResultConsumed: () -> Unit,
    onPickRingtone: (current: String?) -> Unit,
    onDone: (deleted: Boolean) -> Unit,
    viewModel: HabitEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { onDone(it == HabitEditorEvent.Deleted) }
    }
    LaunchedEffect(ringtoneResult) {
        if (ringtoneResult != null) {
            viewModel.setRingtone(ringtoneResult.ifEmpty { null })
            onRingtoneResultConsumed()
        }
    }
    HabitEditorScreen(
        state = state,
        actions = HabitEditorActions(
            onClose = { onDone(false) },
            onSave = viewModel::save,
            onTitle = viewModel::setTitle,
            onToggleDay = viewModel::toggleDay,
            onAddTime = viewModel::addTime,
            onReplaceTime = viewModel::replaceTime,
            onRemoveTime = viewModel::removeTime,
            onInterval = viewModel::setInterval,
            onTts = viewModel::setTts,
            onNotes = viewModel::setNotes,
            onTemplate = viewModel::applyTemplate,
            onPickRingtone = { onPickRingtone(state.form.ringtoneUri) },
            onDelete = viewModel::delete,
        ),
    )
}

/** Which time the picker sheet is editing: a new one, or an existing one. */
private sealed interface TimeTarget {
    data object Add : TimeTarget

    data class Edit(val time: LocalTime) : TimeTarget
}

/**
 * New habit / edit habit (the NewHabit mockup): templates, what it says, the days (spring
 * toggles), one or more ring times on panel chips (mono), how often, ringtone, voice and notes.
 * Save validates; problems show under their field and are announced.
 */
@Composable
fun HabitEditorScreen(state: HabitEditorUiState, actions: HabitEditorActions, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val form = state.form
    val errors = state.visibleErrors
    val haptics = rememberHaptics()
    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .background(c.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CircleIconButton(C2RIcons.Close, stringResource(R.string.habit_discard), actions.onClose)
            Text(
                stringResource(if (state.isNew) R.string.habit_new_title else R.string.habit_edit_title),
                style = C2RTheme.type.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                color = c.ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            PillButton(
                stringResource(R.string.habit_save),
                onClick = {
                    if (state.errors.isNotEmpty()) haptics.perform(Haptic.Reject)
                    actions.onSave()
                },
                height = 44.dp,
                enabled = !state.saving && !state.loading,
                contentPadding = PaddingValues(horizontal = 22.dp),
                textStyle = C2RTheme.type.button.copy(fontSize = 15.sp),
                modifier = Modifier.testTag(HabitTags.SAVE),
            )
        }
        if (state.loading) return@Column
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (state.isNew) {
                Templates(state.template, actions.onTemplate, Modifier.staggeredEntrance(0, distance = 12.dp, staggerMs = 40))
            }
            TitleField(form.title, actions.onTitle, HabitError.TITLE in errors, Modifier.staggeredEntrance(1, distance = 12.dp, staggerMs = 40))
            Days(form.days, actions.onToggleDay, HabitError.DAYS in errors, Modifier.staggeredEntrance(2, distance = 12.dp, staggerMs = 40))
            Times(
                times = form.times,
                error = HabitError.TIMES in errors,
                onAdd = { timeTarget = TimeTarget.Add },
                onEdit = { timeTarget = TimeTarget.Edit(it) },
                modifier = Modifier.staggeredEntrance(3, distance = 12.dp, staggerMs = 40),
            )
            GroupCard(Modifier.staggeredEntrance(4, distance = 12.dp, staggerMs = 40)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .rowDivider(true, c.line)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.habit_repeat_every), style = C2RTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium), color = c.ink)
                        Text(
                            pluralStringResource(R.plurals.habit_weeks, form.intervalWeeks, form.intervalWeeks),
                            style = C2RTheme.type.caption,
                            color = c.muted,
                        )
                    }
                    Stepper(
                        value = form.intervalWeeks,
                        onChange = actions.onInterval,
                        range = HabitValidation.INTERVALS,
                        decreaseLabel = stringResource(R.string.habit_interval_less),
                        increaseLabel = stringResource(R.string.habit_interval_more),
                        valueDescription = stringResource(R.string.habit_repeat_every),
                    )
                }
                NavRow(
                    title = stringResource(R.string.settings_ringtone),
                    value = state.ringtoneTitle ?: stringResource(R.string.habit_ringtone_default),
                    icon = C2RIcons.Music,
                    onClick = actions.onPickRingtone,
                )
                val preview = voicePreview(form)
                ToggleRow(
                    title = stringResource(R.string.habit_read_aloud),
                    subtitle = if (form.ttsEnabled) stringResource(R.string.detail_quote, preview) else stringResource(R.string.habit_read_aloud_off),
                    checked = form.ttsEnabled,
                    onCheckedChange = actions.onTts,
                    modifier = Modifier.rowDivider(true, c.line),
                )
                NotesField(form.notes, actions.onNotes)
            }
            if (!state.isNew) {
                PillButton(
                    stringResource(R.string.detail_delete_habit),
                    onClick = { confirmDelete = true },
                    style = PillStyle.DangerGhost,
                    height = 48.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(HabitTags.DELETE),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    val target = timeTarget
    C2RSheet(
        open = target != null,
        onDismiss = { timeTarget = null },
        label = stringResource(R.string.habit_ring_at),
    ) {
        val editing = target as? TimeTarget.Edit
        TimePickerSheetContent(
            title = stringResource(if (editing == null) R.string.habit_add_time_title else R.string.habit_change_time_title),
            initial = editing?.time ?: suggestTime(form.times),
            onConfirm = { picked ->
                if (editing == null) actions.onAddTime(picked) else actions.onReplaceTime(editing.time, picked)
                timeTarget = null
            },
            onCancel = { timeTarget = null },
            extra = if (editing != null && form.times.size > 1) {
                {
                    PillButton(
                        stringResource(R.string.habit_remove_time),
                        onClick = {
                            actions.onRemoveTime(editing.time)
                            timeTarget = null
                        },
                        style = PillStyle.DangerGhost,
                        height = 48.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                null
            },
        )
    }
    C2RSheet(open = confirmDelete, onDismiss = { confirmDelete = false }, label = stringResource(R.string.detail_delete_title, form.title)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.detail_delete_title, form.title),
                style = C2RTheme.type.sheetTitle,
                color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.detail_delete_body), style = C2RTheme.type.body, color = c.muted)
            PillButton(
                stringResource(R.string.detail_delete_confirm),
                onClick = {
                    confirmDelete = false
                    actions.onDelete()
                },
                style = PillStyle.Danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(HabitTags.DELETE_CONFIRM),
            )
            PillButton(stringResource(R.string.action_cancel), { confirmDelete = false }, Modifier.fillMaxWidth(), style = PillStyle.Ghost, height = 48.dp)
        }
    }
}

/** The next free half hour after the last time (or 9:00), for "Add a time". */
private fun suggestTime(times: List<LocalTime>): LocalTime {
    val last = times.maxOrNull() ?: return LocalTime.of(9, 0)
    var candidate = last.plusHours(1)
    while (candidate in times) candidate = candidate.plusMinutes(30)
    return candidate
}

@Composable
private fun voicePreview(form: HabitForm): String {
    val title = form.title.ifBlank { stringResource(R.string.habit_title_placeholder) }
    val notes = form.notes.trim()
    val time = form.times.firstOrNull()?.let { localTimeText(it) }
    val detail = when {
        notes.isNotEmpty() -> notes.take(SpeechText.MAX_NOTES_CHARS)
        time != null -> time
        else -> null
    }
    return if (detail == null) stringResource(R.string.habit_voice_preview_short, title) else stringResource(R.string.habit_voice_preview, title, detail)
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = C2RTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = C2RTheme.colors.muted, modifier = modifier)
}

@Composable
private fun ErrorText(text: String, tag: String) {
    Text(
        text,
        style = C2RTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
        color = C2RTheme.colors.missed,
        modifier = Modifier
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Templates(selected: HabitTemplate?, onTemplate: (HabitTemplate, String) -> Unit, modifier: Modifier = Modifier) {
    val titles = mapOf(
        HabitTemplate.MEDICINE to stringResource(R.string.habit_template_medicine),
        HabitTemplate.GYM to stringResource(R.string.habit_template_gym),
        HabitTemplate.WATER to stringResource(R.string.habit_template_water),
        HabitTemplate.STANDUP to stringResource(R.string.habit_template_standup),
    )
    val fills = mapOf(
        HabitTemplate.MEDICINE to stringResource(R.string.habit_template_medicine_title),
        HabitTemplate.GYM to stringResource(R.string.habit_template_gym_title),
        HabitTemplate.WATER to stringResource(R.string.habit_template_water_title),
        HabitTemplate.STANDUP to stringResource(R.string.habit_template_standup_title),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(stringResource(R.string.habit_start_from))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HabitTemplate.entries.forEach { template ->
                ChoiceChip(
                    text = titles.getValue(template),
                    selected = template == selected,
                    onClick = { onTemplate(template, fills.getValue(template)) },
                    modifier = Modifier.testTag(HabitTags.template(template)),
                )
            }
        }
    }
}

@Composable
private fun TitleField(value: String, onChange: (String) -> Unit, hasError: Boolean, modifier: Modifier = Modifier) {
    val text = rememberSyncedText(value, HabitValidation.MAX_TITLE, onChange)
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val interaction = remember { MutableInteractionSource() }
    val underline by animateColorAsState(if (hasError) c.missed else c.ink, motion.fade(), label = "titleLine")
    val errorText = stringResource(R.string.habit_error_title)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(stringResource(R.string.habit_title_label))
        BasicTextField(
            value = text.text,
            onValueChange = text::input,
            singleLine = true,
            textStyle = C2RTheme.type.cardTitle.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.ink),
            cursorBrush = SolidColor(c.ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HabitTags.TITLE)
                .semantics { if (hasError) error(errorText) },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(c.surface, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .drawBehind {
                            val h = 2.dp.toPx()
                            drawLine(underline, Offset(0f, size.height - h / 2f), Offset(size.width, size.height - h / 2f), strokeWidth = h)
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.habit_title_placeholder),
                            style = C2RTheme.type.cardTitle.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                            color = c.muted.copy(alpha = 0.7f),
                        )
                    }
                    inner()
                }
            },
        )
        AnimatedVisibility(hasError, enter = expandVertically(motion.precise()) + fadeIn(motion.fade()), exit = shrinkVertically(motion.precise()) + fadeOut(motion.fade())) {
            ErrorText(errorText, HabitTags.ERROR_TITLE)
        }
    }
}

@Composable
private fun Days(days: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit, error: Boolean, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val motion = C2RTheme.motion
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(stringResource(R.string.habit_repeat_on))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (day in localeWeek(locale)) {
                DayToggle(
                    letter = weekdayLetter(day, locale),
                    label = weekdayFull(day, locale),
                    checked = day in days,
                    onCheckedChange = { onToggle(day) },
                    modifier = Modifier.testTag(HabitTags.day(day)),
                )
            }
        }
        AnimatedVisibility(error, enter = expandVertically(motion.precise()) + fadeIn(motion.fade()), exit = shrinkVertically(motion.precise()) + fadeOut(motion.fade())) {
            ErrorText(stringResource(R.string.habit_error_days), HabitTags.ERROR_DAYS)
        }
    }
}

@Composable
private fun Times(
    times: List<LocalTime>,
    error: Boolean,
    onAdd: () -> Unit,
    onEdit: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(stringResource(R.string.habit_ring_at))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            times.forEachIndexed { index, time ->
                val interaction = remember(time) { MutableInteractionSource() }
                val text = localTimeText(time)
                val change = stringResource(R.string.habit_change_time_cd, text)
                Box(
                    Modifier
                        .pressScale(interaction, pressedScale = 0.94f)
                        .height(48.dp)
                        .background(c.panel, C2RTheme.shapes.tile)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = change) { onEdit(time) }
                        .semantics { contentDescription = text }
                        .testTag(HabitTags.time(index))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, style = C2RTheme.type.monoSmall.copy(fontSize = 22.sp), color = c.lamp)
                }
            }
            if (times.size < HabitValidation.MAX_TIMES) {
                val interaction = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .pressScale(interaction, pressedScale = 0.94f)
                        .height(48.dp)
                        .border(1.5.dp, if (error) c.missed else c.line, C2RTheme.shapes.tile)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onAdd)
                        .testTag(HabitTags.ADD_TIME)
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(C2RIcons.Plus, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.habit_add_time), style = C2RTheme.type.button.copy(fontSize = 15.sp), color = c.ink)
                }
            }
        }
        AnimatedVisibility(error, enter = expandVertically(motion.precise()) + fadeIn(motion.fade()), exit = shrinkVertically(motion.precise()) + fadeOut(motion.fade())) {
            ErrorText(stringResource(R.string.habit_error_times), HabitTags.ERROR_TIMES)
        }
    }
}

@Composable
private fun NotesField(value: String, onChange: (String) -> Unit) {
    val text = rememberSyncedText(value, HabitEditorViewModel.MAX_NOTES, onChange)
    val c = C2RTheme.colors
    Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.habit_notes), style = C2RTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium), color = c.ink)
        BasicTextField(
            value = text.text,
            onValueChange = text::input,
            textStyle = C2RTheme.type.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 24.dp)) {
                    if (text.text.isEmpty()) {
                        Text(stringResource(R.string.habit_notes_hint), style = C2RTheme.type.body, color = c.muted)
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * A text field's text held in Compose, so typing never waits on the view model's state round
 * trip (which can lag a keystroke and drop or reorder characters). Every edit is still sent on;
 * a value coming back that isn't one of ours (a template, the loaded habit) replaces the text.
 */
@Stable
internal class SyncedText(initial: String, private val maxLength: Int, private val send: (String) -> Unit) {
    var text by mutableStateOf(initial)
        private set
    private val sent = ArrayDeque<String>()

    fun input(new: String) {
        val clipped = new.take(maxLength)
        text = clipped
        sent.addLast(clipped)
        if (sent.size > MAX_IN_FLIGHT) sent.removeFirst()
        send(clipped)
    }

    /** The view model's value arrived: our own (possibly stale) echoes are ignored. */
    fun external(value: String) {
        when {
            value == text -> sent.clear()
            value in sent -> Unit
            else -> {
                text = value
                sent.clear()
            }
        }
    }

    private companion object {
        const val MAX_IN_FLIGHT = 64
    }
}

@Composable
internal fun rememberSyncedText(value: String, maxLength: Int, onChange: (String) -> Unit): SyncedText {
    val current by rememberUpdatedState(onChange)
    val synced = remember { SyncedText(value, maxLength) { current(it) } }
    LaunchedEffect(value) { synced.external(value) }
    return synced
}
