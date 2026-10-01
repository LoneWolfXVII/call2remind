package app.call2remind.ui.detail

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.R
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.speech.SpeechText
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.C2RSheet
import app.call2remind.ui.components.GroupCard
import app.call2remind.ui.components.LabeledValue
import app.call2remind.ui.components.LampDot
import app.call2remind.ui.components.MonoTime
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.SourceChip
import app.call2remind.ui.components.ToggleRow
import app.call2remind.ui.components.TopBar
import app.call2remind.ui.components.formatLocalTime
import app.call2remind.ui.components.labelRes
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.format.Relative
import app.call2remind.ui.format.ScheduleText
import app.call2remind.ui.format.agoText
import app.call2remind.ui.format.currentLocale
import app.call2remind.ui.format.dayMonth
import app.call2remind.ui.format.habitCadenceText
import app.call2remind.ui.format.leadText
import app.call2remind.ui.format.rememberMinuteTicker
import app.call2remind.ui.format.timeParts
import app.call2remind.ui.navigation.SharedKeys
import app.call2remind.ui.navigation.sharedBoundsIfAvailable
import app.call2remind.ui.navigation.sharedTextIfAvailable
import app.call2remind.ui.system.SystemIntents
import app.call2remind.ui.theme.C2RTheme
import java.time.Instant
import java.time.ZoneId

/** Test tags for Detail. */
object DetailTags {
    const val RING_TOGGLE = "detail_ring_toggle"
    const val SKIP = "detail_skip"
    const val EDIT = "detail_edit"
    const val DELETE = "detail_delete"
    const val DELETE_CONFIRM = "detail_delete_confirm"
}

@Immutable
data class DetailActions(
    val onBack: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onSetRinging: (Boolean) -> Unit = {},
    val onEditInSource: () -> Unit = {},
    val onEditHabit: () -> Unit = {},
    val onDeleteHabit: () -> Unit = {},
)

@Composable
fun DetailRoute(
    sharedKey: String,
    onBack: () -> Unit,
    onEditHabit: (habitId: String) -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val now by rememberMinuteTicker()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { if (it == DetailEvent.Close) onBack() }
    }
    val noApp = stringResource(R.string.detail_no_source_app)
    DetailScreen(
        state = state,
        sharedKey = sharedKey,
        now = now,
        actions = DetailActions(
            onBack = onBack,
            onSkip = viewModel::skip,
            onSetRinging = viewModel::setRinging,
            onEditInSource = {
                state.reminder?.let { r ->
                    if (!SystemIntents.launchFirst(context, SystemIntents.editReminder(r, state.occurrence?.fireAt))) {
                        Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onEditHabit = { state.reminder?.let { onEditHabit(it.externalId) } },
            onDeleteHabit = viewModel::deleteHabit,
        ),
    )
}

/**
 * Reminder detail (the Detail mockup): where it comes from, the title and schedule, the next ring
 * on a caller-ID panel, what the call will say and do, and the actions. The title, the time and
 * (from the strip) the panel itself arrive as shared elements from Up next.
 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    sharedKey: String,
    now: Instant,
    actions: DetailActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val c = C2RTheme.colors
    val reminder = state.reminder
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .background(c.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopBar(onBack = actions.onBack)
        if (reminder == null) return@Column
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceChip(reminder.sourceType, style = C2RTheme.type.caption.copy(fontSize = 15.sp))
                Text(
                    reminder.title,
                    style = C2RTheme.type.callTitleSmall,
                    color = c.ink,
                    modifier = Modifier
                        .semantics { heading() }
                        .sharedTextIfAvailable(SharedKeys.title(sharedKey)),
                )
                Text(scheduleLine(reminder, state, zone), style = C2RTheme.type.body, color = c.muted)
            }
            NextRingPanel(state, sharedKey, now, zone, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp))
            GroupCard(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .staggeredEntrance(1, distance = 12.dp),
            ) {
                LabeledValue(stringResource(R.string.detail_voice_says), voiceLine(reminder, state, zone))
                LabeledValue(stringResource(R.string.detail_if_declined), declineLine(state))
                LabeledValue(stringResource(R.string.detail_ringtone), ringtoneLine(reminder, state))
                ToggleRow(
                    title = stringResource(if (reminder.sourceType == SourceType.HABIT) R.string.detail_ring_habit else R.string.detail_ring_event),
                    checked = reminder.enabled,
                    onCheckedChange = actions.onSetRinging,
                    modifier = Modifier.testTag(DetailTags.RING_TOGGLE),
                )
            }
            if (reminder.sourceType != SourceType.HABIT) {
                SyncedNotice(reminder, state, now, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).staggeredEntrance(2, distance = 12.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
        DetailActionsBar(reminder, state, now, zone, actions, onDelete = { confirmDelete = true })
    }
    C2RSheet(open = confirmDelete, onDismiss = { confirmDelete = false }, label = stringResource(R.string.detail_delete_title, reminder?.title.orEmpty())) {
        DeleteConfirm(
            title = reminder?.title.orEmpty(),
            onDelete = {
                confirmDelete = false
                actions.onDeleteHabit()
            },
            onCancel = { confirmDelete = false },
        )
    }
}

@Composable
private fun scheduleLine(reminder: Reminder, state: DetailUiState, zone: ZoneId): String {
    val locale = currentLocale()
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    return when (val schedule = reminder.schedule) {
        is Schedule.At -> {
            val at = schedule.instant.atZone(zone)
            stringResource(R.string.detail_schedule_at, dayMonth(at.toLocalDate(), locale), timeParts(schedule.instant).text)
        }
        is Schedule.DateOnly -> {
            val date = dayMonth(schedule.date, locale)
            val time = schedule.time
            if (time != null) {
                stringResource(R.string.detail_schedule_at, date, formatLocalTime(time, is24, locale))
            } else {
                val default = state.settings.defaultTimes[reminder.sourceType]
                stringResource(R.string.detail_schedule_date_only, date, formatLocalTime(default, is24, locale))
            }
        }
        is Schedule.Recurring -> {
            val times = schedule.rule.times.sorted().joinToString(", ") { formatLocalTime(it, is24, locale) }
            stringResource(R.string.detail_schedule_habit, habitCadenceText(schedule.rule.daysOfWeek, schedule.rule.intervalWeeks), times)
        }
        is Schedule.Annual -> {
            val time = schedule.time ?: state.settings.defaultTimes[reminder.sourceType]
            val day = java.time.format.DateTimeFormatter.ofPattern("d MMMM", locale).format(schedule.monthDay)
            stringResource(R.string.detail_schedule_yearly, day, formatLocalTime(time, is24, locale))
        }
    }
}

@Composable
private fun NextRingPanel(state: DetailUiState, sharedKey: String, now: Instant, zone: ZoneId, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val reminder = state.reminder ?: return
    val occurrence = state.occurrence?.takeIf { it.state.isActive }
    val ringing = occurrence?.state == OccurrenceState.RINGING
    val status = when {
        !reminder.enabled -> stringResource(R.string.detail_not_ringing)
        occurrence == null -> stringResource(R.string.detail_nothing_scheduled)
        ringing -> stringResource(R.string.upnext_ringing_now)
        occurrence.state == OccurrenceState.SNOOZED -> stringResource(R.string.detail_snoozed_until)
        else -> {
            val day = occurrence.fireAt.atZone(zone).toLocalDate()
            val today = now.atZone(zone).toLocalDate()
            when (day) {
                today -> stringResource(R.string.detail_next_ring_today)
                today.plusDays(1) -> stringResource(R.string.detail_next_ring_tomorrow)
                else -> stringResource(R.string.detail_next_ring_on, dayMonth(day, currentLocale()))
            }
        }
    }
    val lead = when {
        ScheduleText.usesDefaultTime(reminder.schedule) -> stringResource(R.string.detail_lead_default_time, stringResource(reminder.sourceType.labelRes))
        else -> leadText(ScheduleText.lead(reminder.schedule.lead))
    }
    Row(
        modifier
            .sharedBoundsIfAvailable(SharedKeys.callerStrip(sharedKey))
            .fillMaxWidth()
            .background(c.panel, C2RTheme.shapes.strip)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LampDot(lit = occurrence != null && reminder.enabled, halo = true, pulse = ringing, unlitColor = c.onPanelMuted)
                AnimatedContent(
                    targetState = status,
                    transitionSpec = { fadeIn(C2RTheme.motion.fade()) togetherWith fadeOut(C2RTheme.motion.fade()) },
                    label = "detailStatus",
                ) { Text(it, style = C2RTheme.type.caption, color = c.onPanelMuted) }
            }
            if (occurrence != null && reminder.enabled) {
                MonoTime(
                    timeParts(occurrence.fireAt),
                    style = C2RTheme.type.monoLarge.copy(fontSize = 44.sp),
                    modifier = Modifier.sharedTextIfAvailable(SharedKeys.time(sharedKey)),
                )
            } else {
                Text(
                    "--:--",
                    style = C2RTheme.type.monoLarge.copy(fontSize = 44.sp),
                    color = c.onPanel.copy(alpha = 0.35f),
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .sharedTextIfAvailable(SharedKeys.time(sharedKey)),
                )
            }
        }
        Text(lead, style = C2RTheme.type.caption, color = c.onPanelMuted, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f, fill = false))
    }
}

@Composable
private fun voiceLine(reminder: Reminder, state: DetailUiState, zone: ZoneId): String {
    if (!reminder.ttsEnabled || !state.settings.ttsEnabled) return stringResource(R.string.detail_voice_off)
    val planned = state.occurrence?.plannedAt ?: return stringResource(R.string.detail_voice_generic)
    val text = SpeechText.build(reminder, planned, zone, currentLocale())
    return stringResource(R.string.detail_quote, text)
}

@Composable
private fun declineLine(state: DetailUiState): String {
    val minutes = state.settings.snoozeLength.toMinutes()
    val max = state.settings.maxRingBacks
    return if (max <= 0) {
        stringResource(R.string.detail_decline_missed)
    } else {
        stringResource(R.string.detail_decline_ringback, minutes, max)
    }
}

@Composable
private fun ringtoneLine(reminder: Reminder, state: DetailUiState): String {
    val title = state.ringtoneTitle ?: stringResource(R.string.tone_default_alarm)
    return when (state.ringtoneOrigin) {
        RingtoneOrigin.REMINDER -> stringResource(R.string.detail_ringtone_own, title)
        RingtoneOrigin.SOURCE -> stringResource(R.string.detail_ringtone_source, title, stringResource(reminder.sourceType.labelRes))
        RingtoneOrigin.APP_DEFAULT -> stringResource(R.string.detail_ringtone_default, title)
    }
}

/** "Synced from Google Calendar 3 min ago. To change the time or title, edit it there…" */
@Composable
private fun SyncedNotice(reminder: Reminder, state: DetailUiState, now: Instant, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val source = stringResource(reminder.sourceType.labelRes)
    val synced = state.lastSyncAt?.let { agoText(Relative.ago(it, now)) }
    val text = if (synced != null) {
        stringResource(R.string.detail_synced_notice, source, synced)
    } else {
        stringResource(R.string.detail_synced_notice_unknown, source)
    }
    Row(
        modifier
            .fillMaxWidth()
            .border(1.5.dp, c.line, C2RTheme.shapes.box)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(C2RIcons.Sync, contentDescription = null, tint = c.ink, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Text(text, style = C2RTheme.type.caption.copy(lineHeight = 21.sp), color = c.ink)
    }
}

@Composable
private fun DetailActionsBar(
    reminder: Reminder,
    state: DetailUiState,
    now: Instant,
    zone: ZoneId,
    actions: DetailActions,
    onDelete: () -> Unit,
) {
    val habit = state.isHabit
    val occurrenceDay = state.occurrence?.fireAt?.atZone(zone)?.toLocalDate()
    val skipLabel = stringResource(
        if (occurrenceDay == now.atZone(zone).toLocalDate()) R.string.detail_skip_today else R.string.detail_skip_once,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.canSkip && reminder.enabled) {
                PillButton(
                    skipLabel,
                    actions.onSkip,
                    Modifier
                        .weight(1f)
                        .testTag(DetailTags.SKIP),
                    style = PillStyle.Outline,
                )
            }
            when {
                habit -> PillButton(
                    stringResource(R.string.detail_edit_habit),
                    actions.onEditHabit,
                    Modifier
                        .weight(1.4f)
                        .testTag(DetailTags.EDIT),
                    icon = C2RIcons.Sliders,
                )
                reminder.sourceType != SourceType.HABIT -> PillButton(
                    stringResource(R.string.detail_edit_in, stringResource(sourceAppRes(reminder.sourceType))),
                    actions.onEditInSource,
                    Modifier
                        .weight(1.8f)
                        .testTag(DetailTags.EDIT),
                    icon = C2RIcons.OpenExternal,
                    textStyle = C2RTheme.type.button.copy(fontSize = 15.sp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                )
            }
        }
        if (habit) {
            PillButton(
                stringResource(R.string.detail_delete_habit),
                onDelete,
                Modifier
                    .fillMaxWidth()
                    .testTag(DetailTags.DELETE),
                style = PillStyle.DangerGhost,
                height = 48.dp,
            )
        }
    }
}

@Composable
private fun DeleteConfirm(title: String, onDelete: () -> Unit, onCancel: () -> Unit) {
    val c = C2RTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.detail_delete_title, title),
            style = C2RTheme.type.sheetTitle,
            color = c.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.detail_delete_body), style = C2RTheme.type.body, color = c.muted)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PillButton(
                stringResource(R.string.detail_delete_confirm),
                onDelete,
                Modifier
                    .fillMaxWidth()
                    .testTag(DetailTags.DELETE_CONFIRM),
                style = PillStyle.Danger,
            )
            PillButton(stringResource(R.string.action_cancel), onCancel, Modifier.fillMaxWidth(), style = PillStyle.Ghost, height = 48.dp)
        }
    }
}

/** The app a source lives in ("Calendar", "Tasks"), for "Edit in …". */
internal fun sourceAppRes(type: SourceType): Int = when (type) {
    SourceType.CALENDAR -> R.string.app_calendar
    SourceType.GOOGLE_TASKS -> R.string.app_tasks
    SourceType.SAMSUNG_REMINDER -> R.string.app_reminders
    SourceType.MS_TODO -> R.string.app_todo
    SourceType.BIRTHDAY -> R.string.app_contacts
    SourceType.HABIT -> R.string.app_name
}
