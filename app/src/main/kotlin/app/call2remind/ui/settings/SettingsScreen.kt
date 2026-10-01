package app.call2remind.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.BuildConfig
import app.call2remind.R
import app.call2remind.core.model.SourceType
import app.call2remind.settings.ThemeMode
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.C2RSheet
import app.call2remind.ui.components.InfoRow
import app.call2remind.ui.components.NavRow
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.Section
import app.call2remind.ui.components.SegmentedControl
import app.call2remind.ui.components.Stepper
import app.call2remind.ui.components.TimePickerSheetContent
import app.call2remind.ui.components.ToggleRow
import app.call2remind.ui.components.icon
import app.call2remind.ui.components.labelRes
import app.call2remind.ui.components.rowDivider
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.format.localTimeText
import app.call2remind.ui.format.snoozeLengthText
import app.call2remind.ui.ringtone.RingtoneTarget
import app.call2remind.ui.theme.C2RTheme
import kotlinx.coroutines.delay

/** Where Settings sends the user. */
@Immutable
data class SettingsNav(
    val openRingtone: (RingtoneTarget) -> Unit = {},
    val openPermissions: () -> Unit = {},
    val openTestCall: () -> Unit = {},
)

/** Test tags for Settings. */
object SettingsTags {
    const val RING_FOR = "settings_ring_for"
    const val SNOOZE = "settings_snooze"
    const val RING_BACKS = "settings_ring_backs"
    const val TTS = "settings_tts"
    const val THEME = "settings_theme"
    const val TEST_CALL = "settings_test_call"

    fun defaultTime(type: SourceType) = "settings_default_time_${type.name}"

    fun ringtone(type: SourceType?) = "settings_ringtone_${type?.name ?: "default"}"

    fun snoozeChoice(minutes: Long) = "settings_snooze_$minutes"
}

@Immutable
data class SettingsActions(
    val onRingSeconds: (Long) -> Unit = {},
    val onSnoozeMinutes: (Long) -> Unit = {},
    val onRingBacks: (Int) -> Unit = {},
    val onTts: (Boolean) -> Unit = {},
    val onDefaultTime: (SourceType, java.time.LocalTime) -> Unit = { _, _ -> },
    val onTheme: (ThemeMode) -> Unit = {},
)

@Composable
fun SettingsRoute(nav: SettingsNav, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        nav = nav,
        actions = SettingsActions(
            onRingSeconds = viewModel::setRingSeconds,
            onSnoozeMinutes = viewModel::setSnoozeMinutes,
            onRingBacks = viewModel::setMaxRingBacks,
            onTts = viewModel::setTts,
            onDefaultTime = viewModel::setDefaultTime,
            onTheme = viewModel::setTheme,
        ),
    )
}

/** Which sheet Settings has open. */
private sealed interface SettingsSheet {
    data object Snooze : SettingsSheet

    data class DefaultTime(val type: SourceType) : SettingsSheet
}

/**
 * Settings (the Settings mockup): ringing, snooze and missed, times for items without one,
 * ringtones per source, what happens when you can't pick up, appearance, setup checks and about.
 */
@Composable
fun SettingsScreen(state: SettingsUiState, nav: SettingsNav, actions: SettingsActions, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val s = state.settings
    var sheetKind by rememberSaveable { mutableStateOf<String?>(null) }
    val sheet: SettingsSheet? = when {
        sheetKind == SNOOZE_SHEET -> SettingsSheet.Snooze
        sheetKind != null -> SourceType.entries.firstOrNull { it.name == sheetKind }?.let { SettingsSheet.DefaultTime(it) }
        else -> null
    }
    val defaultToneTitle = state.defaultRingtoneTitle ?: stringResource(R.string.tone_default_alarm)

    Column(
        modifier
            .fillMaxSize()
            .background(c.ground),
    ) {
        Text(
            stringResource(R.string.tab_settings),
            style = C2RTheme.type.screenTitle,
            color = c.ink,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 20.dp, top = 14.dp)
                .semantics { heading() },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section(stringResource(R.string.settings_section_ringing), Modifier.staggeredEntrance(0, distance = 12.dp, staggerMs = 40)) {
                NavRow(
                    title = stringResource(R.string.settings_default_ringtone),
                    value = defaultToneTitle,
                    onClick = { nav.openRingtone(RingtoneTarget.Default) },
                    modifier = Modifier.testTag(SettingsTags.ringtone(null)),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .rowDivider(true, c.line)
                        .padding(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.settings_ring_for), style = C2RTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium), color = c.ink)
                    val seconds = s.ringTimeout.seconds
                    SegmentedControl(
                        options = SettingsChoices.RING_SECONDS.map { stringResource(R.string.settings_seconds, it) },
                        selectedIndex = SettingsChoices.RING_SECONDS.indexOf(seconds),
                        onSelect = { actions.onRingSeconds(SettingsChoices.RING_SECONDS[it]) },
                        modifier = Modifier.testTag(SettingsTags.RING_FOR),
                    )
                }
                ToggleRow(
                    title = stringResource(R.string.settings_read_aloud),
                    subtitle = stringResource(R.string.settings_read_aloud_hint),
                    checked = s.ttsEnabled,
                    onCheckedChange = actions.onTts,
                    modifier = Modifier.testTag(SettingsTags.TTS),
                )
            }

            Section(stringResource(R.string.settings_section_snooze), Modifier.staggeredEntrance(1, distance = 12.dp, staggerMs = 40)) {
                NavRow(
                    title = stringResource(R.string.settings_decline_snoozes),
                    value = snoozeLengthText(s.snoozeLength),
                    onClick = { sheetKind = SNOOZE_SHEET },
                    modifier = Modifier.testTag(SettingsTags.SNOOZE),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_ring_backs), style = C2RTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium), color = c.ink)
                        Text(
                            stringResource(if (s.maxRingBacks == 0) R.string.settings_ring_backs_none else R.string.settings_ring_backs_hint),
                            style = C2RTheme.type.caption,
                            color = c.muted,
                        )
                    }
                    Stepper(
                        value = s.maxRingBacks,
                        onChange = actions.onRingBacks,
                        range = SettingsChoices.RING_BACKS,
                        decreaseLabel = stringResource(R.string.settings_fewer),
                        increaseLabel = stringResource(R.string.settings_more),
                        valueDescription = stringResource(R.string.settings_ring_backs),
                        modifier = Modifier.testTag(SettingsTags.RING_BACKS),
                    )
                }
            }

            Section(
                stringResource(R.string.settings_section_no_time),
                Modifier.staggeredEntrance(2, distance = 12.dp, staggerMs = 40),
                footnote = stringResource(R.string.settings_no_time_footnote),
            ) {
                SettingsChoices.DATE_ONLY_SOURCES.forEachIndexed { index, type ->
                    NavRow(
                        title = stringResource(dateOnlyTitle(type)),
                        value = stringResource(R.string.settings_ring_at, localTimeText(s.defaultTimes[type])),
                        icon = type.icon,
                        onClick = { sheetKind = type.name },
                        divider = index != SettingsChoices.DATE_ONLY_SOURCES.lastIndex,
                        modifier = Modifier.testTag(SettingsTags.defaultTime(type)),
                    )
                }
            }

            Section(stringResource(R.string.settings_section_ringtones), Modifier.staggeredEntrance(3, distance = 12.dp, staggerMs = 40)) {
                SettingsChoices.RINGTONE_SOURCES.forEachIndexed { index, type ->
                    val own = s.sourceRingtones[type]
                    NavRow(
                        title = stringResource(type.labelRes),
                        value = if (own == null) {
                            stringResource(R.string.settings_ringtone_same, defaultToneTitle)
                        } else {
                            state.sourceRingtoneTitles[type] ?: stringResource(R.string.settings_ringtone_custom)
                        },
                        icon = type.icon,
                        onClick = { nav.openRingtone(RingtoneTarget.Source(type)) },
                        divider = index != SettingsChoices.RINGTONE_SOURCES.lastIndex,
                        modifier = Modifier.testTag(SettingsTags.ringtone(type)),
                    )
                }
            }

            Section(stringResource(R.string.settings_section_cant_pick_up), Modifier.staggeredEntrance(4, distance = 12.dp, staggerMs = 40)) {
                InfoRow(stringResource(R.string.settings_in_call), stringResource(R.string.settings_in_call_hint), icon = C2RIcons.Phone)
                InfoRow(stringResource(R.string.settings_dnd), stringResource(R.string.settings_dnd_hint), icon = C2RIcons.Moon, divider = false)
            }

            Section(stringResource(R.string.settings_section_appearance), Modifier.staggeredEntrance(5, distance = 12.dp, staggerMs = 40)) {
                Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_theme), style = C2RTheme.type.rowTitle.copy(fontWeight = FontWeight.Medium), color = c.ink)
                    SegmentedControl(
                        options = listOf(
                            stringResource(R.string.settings_theme_system),
                            stringResource(R.string.settings_theme_light),
                            stringResource(R.string.settings_theme_dark),
                        ),
                        selectedIndex = s.themeMode.ordinal,
                        onSelect = { actions.onTheme(ThemeMode.entries[it]) },
                        modifier = Modifier.testTag(SettingsTags.THEME),
                    )
                }
            }

            Section(stringResource(R.string.settings_section_setup), Modifier.staggeredEntrance(6, distance = 12.dp, staggerMs = 40)) {
                NavRow(
                    title = stringResource(R.string.settings_permissions),
                    value = stringResource(R.string.settings_permissions_hint),
                    icon = C2RIcons.Lock,
                    onClick = nav.openPermissions,
                    divider = false,
                )
            }
            PillButton(
                stringResource(R.string.settings_test_call),
                onClick = nav.openTestCall,
                style = PillStyle.Outline,
                height = 52.dp,
                icon = C2RIcons.Phone,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .testTag(SettingsTags.TEST_CALL),
            )
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_about, BuildConfig.VERSION_NAME), style = C2RTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                Text(stringResource(R.string.settings_about_body), style = C2RTheme.type.footnote, color = c.muted)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    C2RSheet(open = sheet == SettingsSheet.Snooze, onDismiss = { sheetKind = null }, label = stringResource(R.string.settings_decline_snoozes)) {
        SnoozeChoice(
            current = s.snoozeLength.toMinutes(),
            onPick = {
                actions.onSnoozeMinutes(it)
                sheetKind = null
            },
        )
    }
    val timeSheet = sheet as? SettingsSheet.DefaultTime
    C2RSheet(open = timeSheet != null, onDismiss = { sheetKind = null }, label = stringResource(R.string.settings_section_no_time)) {
        if (timeSheet != null) {
            TimePickerSheetContent(
                title = stringResource(dateOnlyTitle(timeSheet.type)),
                body = stringResource(dateOnlyBody(timeSheet.type)),
                initial = s.defaultTimes[timeSheet.type],
                onConfirm = {
                    actions.onDefaultTime(timeSheet.type, it)
                    sheetKind = null
                },
                onCancel = { sheetKind = null },
            )
        }
    }
}

private const val SNOOZE_SHEET = "snooze"
private const val SNOOZE_CLOSE_DELAY_MS = 260L

private fun dateOnlyTitle(type: SourceType): Int = when (type) {
    SourceType.CALENDAR -> R.string.settings_time_all_day
    SourceType.GOOGLE_TASKS -> R.string.settings_time_tasks
    SourceType.SAMSUNG_REMINDER -> R.string.source_samsung
    SourceType.BIRTHDAY -> R.string.source_birthday
    SourceType.MS_TODO -> R.string.source_ms_todo
    SourceType.HABIT -> R.string.source_habit
}

private fun dateOnlyBody(type: SourceType): Int = when (type) {
    SourceType.CALENDAR -> R.string.settings_time_all_day_body
    SourceType.GOOGLE_TASKS -> R.string.settings_time_tasks_body
    SourceType.SAMSUNG_REMINDER -> R.string.settings_time_samsung_body
    SourceType.BIRTHDAY -> R.string.settings_time_birthday_body
    SourceType.MS_TODO, SourceType.HABIT -> R.string.settings_time_tasks_body
}

@Composable
private fun SnoozeChoice(current: Long, onPick: (Long) -> Unit) {
    val c = C2RTheme.colors
    // Let the indicator finish its slide before the sheet goes.
    var picked by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(picked) {
        val value = picked ?: return@LaunchedEffect
        delay(SNOOZE_CLOSE_DELAY_MS)
        onPick(value)
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.settings_decline_snoozes),
            style = C2RTheme.type.sheetTitle,
            color = c.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.settings_decline_snoozes_body), style = C2RTheme.type.body, color = c.muted)
        SegmentedControl(
            options = SettingsChoices.SNOOZE_MINUTES.map { stringResource(R.string.snooze_minutes, it) },
            selectedIndex = SettingsChoices.SNOOZE_MINUTES.indexOf(picked ?: current),
            onSelect = { picked = SettingsChoices.SNOOZE_MINUTES[it] },
            modifier = Modifier.testTag(SettingsTags.snoozeChoice(current)),
        )
        Spacer(Modifier.height(8.dp))
    }
}
