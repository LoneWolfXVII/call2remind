@file:OptIn(ExperimentalFoundationApi::class)

package app.call2remind.ui.history

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.R
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.OccurrenceState
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.ChoiceChip
import app.call2remind.ui.components.CircleIconButton
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.SourceChip
import app.call2remind.ui.components.rowDivider
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.format.currentLocale
import app.call2remind.ui.format.dayMonth
import app.call2remind.ui.format.rememberMinuteTicker
import app.call2remind.ui.format.timeParts
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Test tags for History. */
object HistoryTags {
    const val TITLE = "history_title"
    const val LOG = "history_log"
    const val EMPTY = "history_empty"

    fun missed(occurrenceId: String) = "history_missed_$occurrenceId"

    fun ringAgain(occurrenceId: String) = "history_ring_again_$occurrenceId"

    fun done(occurrenceId: String) = "history_done_$occurrenceId"
}

@Immutable
data class HistoryActions(
    val onFilter: (HistoryFilter) -> Unit = {},
    val onToggleLog: () -> Unit = {},
    val onRingAgain: (String) -> Unit = {},
    val onMarkDone: (String) -> Unit = {},
)

@Composable
fun HistoryRoute(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by rememberMinuteTicker()
    HistoryScreen(
        state = state,
        now = now,
        actions = HistoryActions(
            onFilter = viewModel::setFilter,
            onToggleLog = viewModel::toggleLog,
            onRingAgain = viewModel::ringAgain,
            onMarkDone = viewModel::markDone,
        ),
    )
}

/**
 * Missed & history (the Missed mockup). Missed calls are red cards with "Ring me now" / "Mark
 * done"; answered, done and skipped calls follow by day. A long press on the title swaps the list
 * for the ring log (debug), which shows every ring event and why it happened.
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    now: Instant,
    actions: HistoryActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val haptics = rememberHaptics()
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
                stringResource(if (state.showLog) R.string.history_log_title else R.string.tab_history),
                style = C2RTheme.type.screenTitle,
                color = c.ink,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        interactionSource = null,
                        indication = null,
                        onClick = {},
                        onLongClickLabel = stringResource(R.string.history_log_toggle),
                        onLongClick = {
                            haptics.perform(Haptic.Confirm)
                            actions.onToggleLog()
                        },
                    )
                    .semantics { heading() }
                    .testTag(HistoryTags.TITLE),
            )
            if (state.showLog) {
                CircleIconButton(C2RIcons.Close, stringResource(R.string.history_log_close), actions.onToggleLog)
            }
        }
        AnimatedContent(
            targetState = state.showLog,
            transitionSpec = {
                (fadeIn(motion.fade()) + slideInVertically(motion.slide()) { it / 12 }) togetherWith fadeOut(motion.fade())
            },
            label = "historyBody",
        ) { log ->
            if (log) {
                RingLog(state.log, zone)
            } else {
                HistoryList(state, now, zone, actions)
            }
        }
    }
}

@Composable
private fun HistoryList(state: HistoryUiState, now: Instant, zone: ZoneId, actions: HistoryActions) {
    val c = C2RTheme.colors
    val model = state.model
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(stringResource(R.string.history_filter_all), state.filter == HistoryFilter.ALL, { actions.onFilter(HistoryFilter.ALL) })
            ChoiceChip(stringResource(R.string.history_filter_missed), state.filter == HistoryFilter.MISSED, { actions.onFilter(HistoryFilter.MISSED) })
        }
        if (!state.loading && model.isEmpty) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp, vertical = 40.dp)
                    .testTag(HistoryTags.EMPTY),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(if (state.filter == HistoryFilter.MISSED) R.string.history_empty_missed else R.string.history_empty),
                    style = C2RTheme.type.sheetTitle,
                    color = c.ink,
                )
                Text(stringResource(R.string.history_empty_body), style = C2RTheme.type.body, color = c.muted)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (model.missed.isNotEmpty()) {
                item(key = "h-missed") {
                    Header(stringResource(R.string.history_missed_count, model.missed.size), Modifier.animateItem())
                }
                items(model.missed, key = { it.occurrenceId }) { item ->
                    MissedCard(
                        item,
                        now,
                        zone,
                        actions,
                        Modifier
                            .animateItem()
                            .staggeredEntrance(model.missed.indexOf(item).coerceAtMost(6), distance = 12.dp, staggerMs = 40),
                    )
                }
            }
            val today = now.atZone(zone).toLocalDate()
            for (day in model.days) {
                item(key = "d-${day.date}") {
                    val label = when (day.date) {
                        today -> stringResource(R.string.history_earlier_today)
                        today.minusDays(1) -> stringResource(R.string.history_yesterday)
                        else -> dayMonth(day.date, currentLocale())
                    }
                    Header(label, Modifier.animateItem())
                }
                items(day.items, key = { it.occurrenceId }) { item ->
                    HistoryRow(item, Modifier.animateItem())
                }
            }
        }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = C2RTheme.type.section,
        color = C2RTheme.colors.ink,
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun MissedCard(item: HistoryItem, now: Instant, zone: ZoneId, actions: HistoryActions, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val rings = item.ringBacks + 1
    val start = timeParts(item.plannedAt).text
    val day = item.plannedAt.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val detail = when (day) {
        today -> pluralStringResource(R.plurals.history_missed_detail, rings, rings, start)
        today.minusDays(1) -> pluralStringResource(R.plurals.history_missed_detail_yesterday, rings, rings, start)
        else -> pluralStringResource(R.plurals.history_missed_detail_on, rings, rings, start, dayMonth(day, currentLocale()))
    }
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth()
            .background(c.surface, C2RTheme.shapes.card)
            .testTag(HistoryTags.missed(item.occurrenceId))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(c.missed, C2RTheme.shapes.pill), contentAlignment = Alignment.Center) {
                Icon(C2RIcons.Phone, contentDescription = null, tint = c.surface, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = C2RTheme.type.rowTitle.copy(fontSize = 17.sp), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                SourceChip(item.source)
                Text(detail, style = C2RTheme.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.missed)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                stringResource(R.string.history_ring_again),
                onClick = { actions.onRingAgain(item.occurrenceId) },
                style = PillStyle.Outline,
                height = 44.dp,
                icon = C2RIcons.Phone,
                textStyle = C2RTheme.type.button.copy(fontSize = 15.sp),
                modifier = Modifier
                    .weight(1f)
                    .testTag(HistoryTags.ringAgain(item.occurrenceId)),
            )
            PillButton(
                stringResource(R.string.history_mark_done),
                onClick = { actions.onMarkDone(item.occurrenceId) },
                height = 44.dp,
                textStyle = C2RTheme.type.button.copy(fontSize = 15.sp),
                modifier = Modifier
                    .weight(1f)
                    .testTag(HistoryTags.done(item.occurrenceId)),
            )
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val (icon, label) = outcome(item)
    Row(
        modifier
            .fillMaxWidth()
            .rowDivider(true, c.line)
            .padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            timeParts(item.fireAt).text,
            style = C2RTheme.type.monoSmall.copy(fontWeight = FontWeight.SemiBold),
            color = c.muted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(70.dp),
        )
        Text(item.title, style = C2RTheme.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
            Text(label, style = C2RTheme.type.caption, color = c.muted)
        }
    }
}

@Composable
private fun outcome(item: HistoryItem): Pair<ImageVector, String> = when {
    item.state == OccurrenceState.SKIPPED -> C2RIcons.Close to stringResource(R.string.history_outcome_skipped)
    item.state == OccurrenceState.DONE && item.ringBacks > 0 -> C2RIcons.Snooze to stringResource(R.string.history_outcome_snoozed_done)
    item.state == OccurrenceState.DONE -> C2RIcons.Check to stringResource(R.string.history_outcome_done)
    else -> C2RIcons.Phone to stringResource(R.string.history_outcome_missed)
}

/** The ring log: a terse, mono, newest-first trace of what the ringing machinery did and why. */
@Composable
private fun RingLog(lines: List<LogLine>, zone: ZoneId) {
    val c = C2RTheme.colors
    val clock = DateTimeFormatter.ofPattern("MMM d HH:mm:ss", currentLocale())
    if (lines.isEmpty()) {
        Text(
            stringResource(R.string.history_log_empty),
            style = C2RTheme.type.body,
            color = c.muted,
            modifier = Modifier
                .padding(24.dp)
                .testTag(HistoryTags.LOG),
        )
        return
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag(HistoryTags.LOG),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        items(lines.size) { index ->
            val line = lines[index]
            val event = line.event
            Row(
                Modifier
                    .fillMaxWidth()
                    .rowDivider(true, c.line)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    clock.format(event.timestamp.atZone(zone)),
                    style = C2RTheme.type.monoSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = c.muted,
                    modifier = Modifier.width(112.dp),
                )
                LogBadge(event.type)
                Column(Modifier.weight(1f)) {
                    Text(
                        line.title ?: event.occurrenceId,
                        style = C2RTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    event.reason?.let {
                        Text(it, style = C2RTheme.type.monoSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = c.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogBadge(type: RingLogType) {
    val c = C2RTheme.colors
    val (bg, fg) = when (type) {
        RingLogType.FIRED -> c.lamp to c.onLamp
        RingLogType.ANSWERED, RingLogType.DONE -> c.ink to c.surface
        RingLogType.MISSED, RingLogType.FAILED -> c.missed to c.surface
        RingLogType.SNOOZED, RingLogType.DEFERRED, RingLogType.SKIPPED -> c.tint to c.ink
    }
    Text(
        type.name,
        style = C2RTheme.type.monoSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
        color = fg,
        modifier = Modifier
            .background(bg, C2RTheme.shapes.tileSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
