package app.call2remind.ui.sources

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.R
import app.call2remind.core.model.SourceType
import app.call2remind.sources.habit.Habit
import app.call2remind.sync.SyncCoordinator
import app.call2remind.sync.SyncState
import app.call2remind.sync.SyncStatus
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.C2RSheet
import app.call2remind.ui.components.LampDot
import app.call2remind.ui.components.LampSwitch
import app.call2remind.ui.components.NavRow
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.Section
import app.call2remind.ui.components.SyncButton
import app.call2remind.ui.components.Tag
import app.call2remind.ui.components.ToggleRow
import app.call2remind.ui.components.icon
import app.call2remind.ui.components.labelRes
import app.call2remind.ui.components.pressScale
import app.call2remind.ui.components.rowDivider
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.format.Relative
import app.call2remind.ui.format.agoText
import app.call2remind.ui.format.habitCadenceText
import app.call2remind.ui.format.localTimeText
import app.call2remind.ui.format.rememberMinuteTicker
import app.call2remind.ui.system.SystemIntents
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics
import java.time.Instant

/** Test tags for Sources. */
object SourcesTags {
    fun row(type: SourceType) = "source_row_${type.name}"

    fun toggle(type: SourceType) = "source_toggle_${type.name}"

    fun action(type: SourceType) = "source_action_${type.name}"

    const val CALENDAR_PICKER = "source_calendar_picker"
    const val HABITS = "source_habits"
    const val DAY_BEFORE = "source_birthday_day_before"
    const val CONNECT_SHEET = "source_connect_sheet"
}

/** What the Sources screen can ask for. */
@Immutable
data class SourcesActions(
    val onToggle: (SourceType, Boolean) -> Unit = { _, _ -> },
    val onFix: (SourceType, SyncState) -> Unit = { _, _ -> },
    val onSyncAll: () -> Unit = {},
    val onOpenCalendars: () -> Unit = {},
    val onCalendarIncluded: (Long, Boolean) -> Unit = { _, _ -> },
    val onCalendarsClosed: () -> Unit = {},
    val onDayBefore: (Boolean) -> Unit = {},
    val onHabitEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val onEditHabit: (String) -> Unit = {},
    val onNewHabit: () -> Unit = {},
)

@Composable
fun SourcesRoute(
    onNewHabit: () -> Unit,
    onEditHabit: (String) -> Unit,
    viewModel: SourcesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by rememberMinuteTicker()
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    // Runtime permissions: ask; after a refusal Android won't show the dialog again, so send the
    // user to the app's settings page instead.
    var asked by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var pendingType by rememberSaveable { mutableStateOf<SourceType?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refresh()
        val type = pendingType
        if (granted && type != null) viewModel.syncOne(type)
        pendingType = null
    }
    SourcesScreen(
        state = state,
        now = now,
        actions = SourcesActions(
            onToggle = viewModel::setEnabled,
            onFix = { type, syncState ->
                val permission = (syncState as? SyncState.NeedsPermission)?.permission
                when {
                    permission == SyncCoordinator.NOTIFICATION_LISTENER_PERMISSION || type == SourceType.SAMSUNG_REMINDER ->
                        SystemIntents.launchFirst(context, SystemIntents.notificationListener(viewModel.listenerComponent))
                    permission != null -> {
                        val activity = context as? Activity
                        val blocked = permission in asked && activity?.shouldShowRequestPermissionRationale(permission) == false
                        if (blocked) {
                            SystemIntents.launchFirst(context, listOf(SystemIntents.appDetails(context)))
                        } else {
                            asked = asked + permission
                            pendingType = type
                            launcher.launch(permission)
                        }
                    }
                    else -> viewModel.syncOne(type)
                }
            },
            onSyncAll = viewModel::syncAll,
            onOpenCalendars = viewModel::loadCalendars,
            onCalendarIncluded = viewModel::setCalendarIncluded,
            onCalendarsClosed = viewModel::onCalendarsClosed,
            onDayBefore = viewModel::setBirthdayDayBefore,
            onHabitEnabled = viewModel::setHabitEnabled,
            onEditHabit = onEditHabit,
            onNewHabit = onNewHabit,
        ),
    )
}

/** Which sheet is open on Sources. */
private enum class SourcesSheet { NONE, CALENDARS, HABITS, CONNECT_TASKS, CONNECT_TODO }

/**
 * Sources (the Sources mockup): every place reminders come from, with a status lamp (synced,
 * syncing, error, needs permission, not connected, off), when it last synced, its switch and the
 * one action that fixes it.
 */
@Composable
fun SourcesScreen(state: SourcesUiState, now: Instant, actions: SourcesActions, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    var sheet by rememberSaveable { mutableStateOf(SourcesSheet.NONE) }
    Column(
        modifier
            .fillMaxSize()
            .background(c.ground),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.tab_sources),
                style = C2RTheme.type.screenTitle,
                color = c.ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            SyncButton(
                syncing = state.summary == SourcesSummary.Syncing,
                contentDescription = stringResource(R.string.cd_sync_all),
                onClick = actions.onSyncAll,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Summary(state.summary, now, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(18.dp))

            Section(stringResource(R.string.sources_section_device), Modifier.staggeredEntrance(0, distance = 12.dp, staggerMs = 40)) {
                SourceRow(SourceType.CALENDAR, stringResource(R.string.sources_calendar_title), state, now, actions, divider = true)
                val calendarOn = state.settings.isEnabled(SourceType.CALENDAR) && state.status(SourceType.CALENDAR)?.state !is SyncState.NeedsPermission
                AnimatedVisibility(calendarOn, enter = expandVertically(C2RTheme.motion.precise()) + fadeIn(C2RTheme.motion.fade()), exit = shrinkVertically(C2RTheme.motion.precise()) + fadeOut(C2RTheme.motion.fade())) {
                    val excluded = state.settings.excludedCalendarIds.size
                    NavRow(
                        title = stringResource(R.string.sources_choose_calendars),
                        value = if (excluded == 0) {
                            stringResource(R.string.sources_all_calendars)
                        } else {
                            pluralStringResource(R.plurals.sources_calendars_excluded, excluded, excluded)
                        },
                        onClick = {
                            actions.onOpenCalendars()
                            sheet = SourcesSheet.CALENDARS
                        },
                        modifier = Modifier
                            .padding(start = 54.dp)
                            .testTag(SourcesTags.CALENDAR_PICKER),
                    )
                }
                SourceRow(SourceType.BIRTHDAY, stringResource(R.string.source_birthday), state, now, actions, divider = state.settings.isEnabled(SourceType.BIRTHDAY))
                AnimatedVisibility(state.settings.isEnabled(SourceType.BIRTHDAY), enter = expandVertically(C2RTheme.motion.precise()) + fadeIn(C2RTheme.motion.fade()), exit = shrinkVertically(C2RTheme.motion.precise()) + fadeOut(C2RTheme.motion.fade())) {
                    ToggleRow(
                        title = stringResource(R.string.sources_day_before),
                        subtitle = stringResource(R.string.sources_day_before_hint),
                        checked = state.settings.birthdayDayBefore,
                        onCheckedChange = actions.onDayBefore,
                        modifier = Modifier
                            .padding(start = 54.dp)
                            .testTag(SourcesTags.DAY_BEFORE),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Section(
                stringResource(R.string.sources_section_tasks),
                Modifier.staggeredEntrance(1, distance = 12.dp, staggerMs = 40),
                footnote = stringResource(R.string.sources_samsung_footnote),
            ) {
                SourceRow(SourceType.GOOGLE_TASKS, stringResource(R.string.source_google_tasks), state, now, actions, onConnect = { sheet = SourcesSheet.CONNECT_TASKS })
                SourceRow(SourceType.MS_TODO, stringResource(R.string.source_ms_todo), state, now, actions, onConnect = { sheet = SourcesSheet.CONNECT_TODO })
                SourceRow(SourceType.SAMSUNG_REMINDER, stringResource(R.string.source_samsung), state, now, actions, divider = false, tag = stringResource(R.string.sources_best_effort))
            }
            Spacer(Modifier.height(18.dp))
            Section(stringResource(R.string.sources_section_app), Modifier.staggeredEntrance(2, distance = 12.dp, staggerMs = 40)) {
                NavRow(
                    title = stringResource(R.string.sources_habits),
                    value = if (state.habits.isEmpty()) {
                        stringResource(R.string.sources_habits_none)
                    } else {
                        pluralStringResource(R.plurals.sources_habits_count, state.habits.size, state.habits.size, state.habits.joinToString(", ") { it.title })
                    },
                    icon = C2RIcons.Repeat,
                    onClick = { sheet = SourcesSheet.HABITS },
                    divider = false,
                    modifier = Modifier.testTag(SourcesTags.HABITS),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    C2RSheet(
        open = sheet == SourcesSheet.CALENDARS,
        onDismiss = {
            sheet = SourcesSheet.NONE
            actions.onCalendarsClosed()
        },
        label = stringResource(R.string.sources_calendars_sheet_title),
    ) {
        CalendarPicker(state.calendars, actions.onCalendarIncluded)
    }
    C2RSheet(open = sheet == SourcesSheet.HABITS, onDismiss = { sheet = SourcesSheet.NONE }, label = stringResource(R.string.sources_habits)) {
        HabitsSheet(
            habits = state.habits,
            onEnabled = actions.onHabitEnabled,
            onEdit = {
                sheet = SourcesSheet.NONE
                actions.onEditHabit(it)
            },
            onNew = {
                sheet = SourcesSheet.NONE
                actions.onNewHabit()
            },
        )
    }
    C2RSheet(
        open = sheet == SourcesSheet.CONNECT_TASKS || sheet == SourcesSheet.CONNECT_TODO,
        onDismiss = { sheet = SourcesSheet.NONE },
        label = stringResource(R.string.sources_connect_title),
    ) {
        ConnectSoon(tasks = sheet == SourcesSheet.CONNECT_TASKS, onOk = { sheet = SourcesSheet.NONE })
    }
}

@Composable
private fun Summary(summary: SourcesSummary, now: Instant, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val text = when (summary) {
        SourcesSummary.Syncing -> stringResource(R.string.sources_summary_syncing)
        is SourcesSummary.NeedsAttention -> pluralStringResource(R.plurals.sources_summary_attention, summary.count, summary.count)
        is SourcesSummary.Synced -> summary.at?.let { stringResource(R.string.sources_summary_synced, agoText(Relative.ago(it, now))) }
            ?: stringResource(R.string.sources_summary_never)
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (summary) {
            is SourcesSummary.NeedsAttention -> Box(Modifier.size(9.dp).background(c.missed, C2RTheme.shapes.pill))
            else -> LampDot(lit = true, size = 9.dp, pulse = summary == SourcesSummary.Syncing)
        }
        Text(text, style = C2RTheme.type.body.copy(fontSize = C2RTheme.type.section.fontSize), color = c.muted)
    }
}

/** A source's lamp: lit when synced, pulsing while syncing, red on error, dark when it can't run. */
@Composable
private fun StatusLamp(state: SyncState?, lastSyncAt: Instant?, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    when (state) {
        is SyncState.Error -> Box(modifier.size(11.dp).background(c.missed, C2RTheme.shapes.pill))
        SyncState.Syncing -> LampDot(lit = true, pulse = true, size = 11.dp, modifier = modifier)
        SyncState.Idle, null -> LampDot(lit = lastSyncAt != null || state == SyncState.Idle, size = 11.dp, modifier = modifier)
        SyncState.Disabled -> LampDot(lit = false, size = 11.dp, unlitColor = c.line, modifier = modifier)
        is SyncState.NeedsPermission, SyncState.NotConnected -> LampDot(lit = false, size = 11.dp, unlitColor = c.muted, modifier = modifier)
    }
}

@Composable
private fun statusText(type: SourceType, status: SyncStatus?, now: Instant): String {
    val state = status?.state
    return when (state) {
        SyncState.Disabled -> stringResource(R.string.source_state_off)
        SyncState.Syncing -> stringResource(R.string.source_state_syncing)
        is SyncState.Error ->
            if (state.retryable) stringResource(R.string.source_state_error_retry) else stringResource(R.string.source_state_error, state.message)
        is SyncState.NeedsPermission -> stringResource(
            when (state.permission) {
                Manifest.permission.READ_CALENDAR -> R.string.source_state_needs_calendar
                Manifest.permission.READ_CONTACTS -> R.string.source_state_needs_contacts
                else -> R.string.source_state_needs_notifications
            },
        )
        SyncState.NotConnected -> stringResource(R.string.source_state_not_connected)
        SyncState.Idle, null -> {
            val at = status?.lastSyncAt
            when {
                type == SourceType.SAMSUNG_REMINDER -> stringResource(R.string.source_state_listening)
                at != null -> stringResource(R.string.source_state_synced, agoText(Relative.ago(at, now)))
                else -> stringResource(R.string.source_state_waiting)
            }
        }
    }
}

/**
 * One source: icon tile (with its status lamp), name, status line, and its switch — or "Connect"
 * for an account that isn't connected. A missing permission adds an "Allow" pill under the status.
 * The whole row toggles, like the settings rows.
 */
@Composable
private fun SourceRow(
    type: SourceType,
    title: String,
    state: SourcesUiState,
    now: Instant,
    actions: SourcesActions,
    divider: Boolean = true,
    tag: String? = null,
    onConnect: (() -> Unit)? = null,
) {
    val c = C2RTheme.colors
    val haptics = rememberHaptics()
    val status = state.status(type)
    val enabled = state.settings.isEnabled(type)
    val syncState = status?.state
    val notConnected = syncState == SyncState.NotConnected && enabled
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .rowDivider(divider, c.line)
            .then(
                if (notConnected) {
                    Modifier
                } else {
                    Modifier.toggleable(
                        value = enabled,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Switch,
                        onValueChange = {
                            haptics.perform(Haptic.SegmentTick)
                            actions.onToggle(type, it)
                        },
                    )
                },
            )
            .testTag(SourcesTags.row(type))
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp)) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(c.ground, C2RTheme.shapes.tile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(type.icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
            }
            StatusLamp(syncState, status?.lastSyncAt, Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = C2RTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium), color = c.ink)
                if (tag != null) Tag(tag)
            }
            Text(
                statusText(type, status, now),
                style = C2RTheme.type.caption,
                color = if (syncState is SyncState.Error) c.missed else c.muted,
            )
            if (enabled && syncState is SyncState.NeedsPermission) {
                PillButton(
                    stringResource(R.string.setup_allow),
                    onClick = { actions.onFix(type, syncState) },
                    style = PillStyle.Primary,
                    height = 40.dp,
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag(SourcesTags.action(type)),
                )
            }
            if (enabled && syncState is SyncState.Error) {
                PillButton(
                    stringResource(R.string.source_retry),
                    onClick = { actions.onFix(type, syncState) },
                    style = PillStyle.Outline,
                    height = 40.dp,
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag(SourcesTags.action(type)),
                )
            }
        }
        if (notConnected && onConnect != null) {
            PillButton(
                stringResource(R.string.source_connect),
                onClick = onConnect,
                height = 44.dp,
                contentPadding = PaddingValues(horizontal = 18.dp),
                textStyle = C2RTheme.type.button.copy(fontSize = C2RTheme.type.section.fontSize),
                modifier = Modifier.testTag(SourcesTags.action(type)),
            )
        } else {
            LampSwitch(enabled, Modifier.pressScale(interaction, pressedScale = 0.94f).testTag(SourcesTags.toggle(type)))
        }
    }
}

@Composable
private fun CalendarPicker(calendars: List<CalendarChoice>?, onIncluded: (Long, Boolean) -> Unit) {
    val c = C2RTheme.colors
    Text(
        stringResource(R.string.sources_calendars_sheet_title),
        style = C2RTheme.type.sheetTitle,
        color = c.ink,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        stringResource(R.string.sources_calendars_sheet_body),
        style = C2RTheme.type.body,
        color = c.muted,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
    when {
        calendars == null -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            LampDot(lit = true, pulse = true)
        }
        calendars.isEmpty() -> Text(stringResource(R.string.sources_calendars_none), style = C2RTheme.type.body, color = c.ink, modifier = Modifier.padding(vertical = 16.dp))
        else -> calendars.forEachIndexed { index, choice ->
            val info = choice.info
            ToggleRow(
                title = info.displayName ?: info.accountName ?: stringResource(R.string.sources_calendar_unnamed),
                subtitle = info.accountName?.takeIf { it != info.displayName },
                checked = choice.included,
                onCheckedChange = { onIncluded(info.id, it) },
                modifier = Modifier
                    .rowDivider(index != calendars.lastIndex, c.line)
                    .staggeredEntrance(index.coerceAtMost(10), distance = 8.dp),
            )
        }
    }
}

@Composable
private fun HabitsSheet(habits: List<Habit>, onEnabled: (String, Boolean) -> Unit, onEdit: (String) -> Unit, onNew: () -> Unit) {
    val c = C2RTheme.colors
    Text(stringResource(R.string.sources_habits), style = C2RTheme.type.sheetTitle, color = c.ink, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    if (habits.isEmpty()) {
        Text(stringResource(R.string.sources_habits_empty), style = C2RTheme.type.body, color = c.muted, modifier = Modifier.padding(vertical = 8.dp))
    }
    habits.forEachIndexed { index, habit ->
        val times = habit.rule.times.sorted().map { localTimeText(it) }.joinToString(", ")
        Row(
            Modifier
                .fillMaxWidth()
                .rowDivider(true, c.line),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavRow(
                title = habit.title,
                value = stringResource(R.string.sources_habit_line, habitCadenceText(habit.rule.daysOfWeek, habit.rule.intervalWeeks), times),
                onClick = { onEdit(habit.id) },
                divider = false,
                modifier = Modifier
                    .weight(1f)
                    .staggeredEntrance(index.coerceAtMost(10), distance = 8.dp),
                trailing = { },
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .toggleable(
                        value = habit.enabled,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Switch,
                        onValueChange = { onEnabled(habit.id, it) },
                    )
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                LampSwitch(habit.enabled, Modifier.pressScale(interaction, pressedScale = 0.94f))
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    PillButton(stringResource(R.string.action_new_habit), onNew, Modifier.fillMaxWidth(), icon = C2RIcons.Plus)
}

@Composable
private fun ConnectSoon(tasks: Boolean, onOk: () -> Unit) {
    val c = C2RTheme.colors
    val name = stringResource(if (tasks) SourceType.GOOGLE_TASKS.labelRes else SourceType.MS_TODO.labelRes)
    Column(Modifier.testTag(SourcesTags.CONNECT_SHEET), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.sources_connect_heading, name),
            style = C2RTheme.type.sheetTitle,
            color = c.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.sources_connect_body, name), style = C2RTheme.type.body, color = c.muted)
        if (!tasks) {
            Text(stringResource(R.string.sources_connect_todo_hint), style = C2RTheme.type.body, color = c.muted)
        }
        Spacer(Modifier.height(4.dp))
        PillButton(stringResource(R.string.action_ok), onOk, Modifier.fillMaxWidth())
    }
}
