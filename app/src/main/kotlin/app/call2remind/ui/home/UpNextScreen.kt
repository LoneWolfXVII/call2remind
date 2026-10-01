@file:OptIn(ExperimentalMaterial3Api::class)

package app.call2remind.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.call2remind.R
import app.call2remind.core.model.OccurrenceState
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.LampDot
import app.call2remind.ui.components.LampPulse
import app.call2remind.ui.components.MonoTime
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.SourceChip
import app.call2remind.ui.components.SwipeAction
import app.call2remind.ui.components.SwipeActionRow
import app.call2remind.ui.components.SyncButton
import app.call2remind.ui.components.UndoBar
import app.call2remind.ui.components.pressScale
import app.call2remind.ui.components.pressTint
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.format.Relative
import app.call2remind.ui.format.currentLocale
import app.call2remind.ui.format.dayMonth
import app.call2remind.ui.format.habitCadenceText
import app.call2remind.ui.format.timeParts
import app.call2remind.ui.format.untilText
import app.call2remind.ui.navigation.SharedKeys
import app.call2remind.ui.navigation.sharedBoundsIfAvailable
import app.call2remind.ui.navigation.sharedTextIfAvailable
import app.call2remind.ui.theme.C2RTheme
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Test tags for Up next. */
object UpNextTags {
    const val LIST = "upnext_list"
    const val STRIP = "upnext_strip"
    const val COMPACT = "upnext_compact"
    const val FAB = "upnext_fab"
    const val EMPTY = "upnext_empty"
    const val SYNC = "upnext_sync"

    fun row(occurrenceId: String) = "upnext_row_$occurrenceId"
}

/** Everything the Up next screen can ask for. */
@Immutable
data class UpNextActions(
    val onOpen: (row: TimelineRow, sharedKey: String) -> Unit = { _, _ -> },
    val onOpenCall: (occurrenceId: String) -> Unit = {},
    val onSwipe: (TimelineRow, SwipeKind) -> Unit = { _, _ -> },
    val onUndo: () -> Unit = {},
    val onUndoTimeout: () -> Unit = {},
    val onSync: () -> Unit = {},
    val onNewHabit: () -> Unit = {},
    val onCheckSources: () -> Unit = {},
)

/** Shared-element key of a row / the strip opening Detail. */
object UpNextShared {
    fun strip(occurrenceId: String) = "strip-$occurrenceId"

    fun row(occurrenceId: String) = "row-$occurrenceId"
}

/**
 * Up next. The caller-ID strip for the next call sits above the timeline and collapses, as the
 * list scrolls, into a compact panel pill in the top bar ([CollapsingHeaderState], scroll-linked
 * through nested scroll). Rows swipe right for Done and left for Skip today, with an undo bar.
 */
@Composable
fun UpNextScreen(
    state: UpNextUiState,
    now: Instant,
    actions: UpNextActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    listState: LazyListState = rememberLazyListState(),
    header: CollapsingHeaderState = rememberCollapsingHeaderState(),
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    header.settleSpec = motion.precise()
    val model = state.model
    val pull = rememberPullToRefreshState()

    // The orchestrated first-load entrance plays once; rows composed later just appear.
    var playEntrance by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(state.loading) {
        if (!state.loading && playEntrance) {
            kotlinx.coroutines.delay(ENTRANCE_WINDOW_MS)
            playEntrance = false
        }
    }
    val fabExpanded by remember { derivedStateOf { !header.isCollapsed } }

    Box(
        modifier
            .fillMaxSize()
            .background(c.ground)
            .pullToRefresh(isRefreshing = state.refreshing, state = pull, onRefresh = actions.onSync),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .nestedScroll(header.connection),
        ) {
            HeaderBar(
                header = header,
                next = model.next,
                syncing = state.syncing,
                onSync = actions.onSync,
                onOpenNext = { model.next?.let { actions.onOpen(it, UpNextShared.strip(it.occurrenceId)) } },
            )
            val next = model.next
            if (!state.loading && next != null) {
                CollapsingStrip(
                    header = header,
                    row = next,
                    now = now,
                    zone = zone,
                    onClick = { actions.onOpen(next, UpNextShared.strip(next.occurrenceId)) },
                    modifier = Modifier.staggeredEntrance(0, distance = 12.dp, staggerMs = STAGGER_MS, enabled = playEntrance),
                )
            } else {
                SideEffect { header.maxCollapsePx = 0f }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(UpNextTags.LIST),
                contentPadding = PaddingValues(bottom = 112.dp),
            ) {
                if (!state.loading) {
                    if (model.isEmpty) {
                        item(key = "empty") {
                            EmptyHome(
                                onNewHabit = actions.onNewHabit,
                                onCheckSources = actions.onCheckSources,
                                modifier = Modifier.staggeredEntrance(0, distance = 12.dp, staggerMs = STAGGER_MS, enabled = playEntrance),
                            )
                        }
                    }
                    timelineItems(model, now, zone, actions, playEntrance)
                }
            }
        }

        PullIndicator(pull, state.refreshing, Modifier.align(Alignment.TopCenter))

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!model.isEmpty) {
                NewHabitFab(expanded = fabExpanded, onClick = actions.onNewHabit)
            }
            AnimatedVisibility(
                visible = state.pending != null,
                enter = expandVertically(motion.default(), expandFrom = Alignment.Top) + fadeIn(motion.fade()),
                exit = shrinkVertically(motion.precise(), shrinkTowards = Alignment.Top) + fadeOut(motion.fade()),
            ) {
                val pending = remember { mutableStateOf(state.pending) }
                if (state.pending != null) pending.value = state.pending
                pending.value?.let { p ->
                    UndoBar(
                        key = p,
                        message = when (p.kind) {
                            SwipeKind.DONE -> stringResource(R.string.undo_done, p.title)
                            SwipeKind.SKIP -> stringResource(R.string.undo_skipped, p.title)
                        },
                        actionLabel = stringResource(R.string.action_undo),
                        onUndo = actions.onUndo,
                        onTimeout = actions.onUndoTimeout,
                    )
                }
            }
        }
    }
}

private const val STAGGER_MS = 40L
private const val ENTRANCE_WINDOW_MS = 900L
private const val MAX_STAGGERED = 12

private fun LazyListScope.timelineItems(
    model: UpNextModel,
    now: Instant,
    zone: ZoneId,
    actions: UpNextActions,
    playEntrance: Boolean,
) {
    var index = 1
    if (model.ringing.isNotEmpty()) {
        val headerIndex = index++
        item(key = "h-ringing", contentType = "header") {
            GroupHeader(
                stringResource(R.string.upnext_ringing_now),
                Modifier
                    .animateItem()
                    .staggeredEntrance(headerIndex, distance = 12.dp, staggerMs = STAGGER_MS, enabled = playEntrance),
            )
        }
        for (row in model.ringing) {
            val rowIndex = index++
            item(key = row.occurrenceId, contentType = "ringing") {
                RingingRow(
                    row,
                    onClick = { actions.onOpenCall(row.occurrenceId) },
                    modifier = Modifier
                        .animateItem()
                        .staggeredEntrance(rowIndex.coerceAtMost(MAX_STAGGERED), distance = 12.dp, staggerMs = STAGGER_MS, enabled = playEntrance),
                )
            }
        }
    }
    for (section in model.sections) {
        val headerIndex = index++
        item(key = "h-${section.group}", contentType = "header") {
            val locale = currentLocale()
            val date = dayMonth(section.date, locale)
            GroupHeader(
                when (section.group) {
                    TimelineGroup.TODAY -> stringResource(R.string.section_today, date)
                    TimelineGroup.TOMORROW -> stringResource(R.string.section_tomorrow, date)
                    TimelineGroup.LATER -> stringResource(R.string.section_later)
                },
                Modifier
                    .animateItem()
                    .staggeredEntrance(headerIndex.coerceAtMost(MAX_STAGGERED), distance = 12.dp, staggerMs = STAGGER_MS, enabled = playEntrance),
            )
        }
        section.rows.forEachIndexed { i, row ->
            val rowIndex = index++
            val last = i == section.rows.lastIndex
            item(key = row.occurrenceId, contentType = "row") {
                TimelineRowItem(
                    row = row,
                    showDate = section.group == TimelineGroup.LATER,
                    connector = !last,
                    zone = zone,
                    now = now,
                    actions = actions,
                    modifier = Modifier
                        .animateItem()
                        .staggeredEntrance(rowIndex.coerceAtMost(MAX_STAGGERED), distance = 12.dp, staggerMs = STAGGER_MS, enabled = playEntrance),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Header: title bar + collapsing caller-ID strip
// ---------------------------------------------------------------------------------------------

@Composable
private fun HeaderBar(
    header: CollapsingHeaderState,
    next: TimelineRow?,
    syncing: Boolean,
    onSync: () -> Unit,
    onOpenNext: () -> Unit,
) {
    val c = C2RTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Text(
                stringResource(R.string.tab_up_next),
                style = C2RTheme.type.screenTitle,
                color = c.ink,
                modifier = Modifier
                    .semantics { heading() }
                    .graphicsLayer {
                        val f = header.fraction
                        alpha = (1f - f * 1.6f).coerceIn(0f, 1f)
                        translationY = -f * 14.dp.toPx()
                    },
            )
            val showCompact by remember { derivedStateOf { header.fraction > 0.3f } }
            if (next != null && showCompact) {
                CompactNext(next, header, onOpenNext)
            }
        }
        SyncButton(
            syncing = syncing,
            contentDescription = stringResource(if (syncing) R.string.cd_syncing else R.string.cd_sync_now),
            onClick = onSync,
            modifier = Modifier.testTag(UpNextTags.SYNC),
        )
    }
}

/** The strip, folded into the top bar: a small panel pill with the lamp, the time and the title. */
@Composable
private fun CompactNext(row: TimelineRow, header: CollapsingHeaderState, onClick: () -> Unit) {
    val c = C2RTheme.colors
    val collapsed = header.isCollapsed
    val time = timeParts(row.fireAt)
    Row(
        Modifier
            .graphicsLayer {
                val f = ((header.fraction - 0.45f) / 0.55f).coerceIn(0f, 1f)
                alpha = f
                translationY = (1f - f) * 18.dp.toPx()
                val s = 0.92f + 0.08f * f
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .heightIn(min = 48.dp)
            .background(c.panel, C2RTheme.shapes.pill)
            .clickable(interactionSource = null, indication = null, enabled = collapsed, onClickLabel = row.title, onClick = onClick)
            .testTag(UpNextTags.COMPACT)
            .padding(start = 14.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LampDot(lit = true, size = 8.dp)
        Text(time.text, style = C2RTheme.type.monoSmall.copy(fontSize = 16.sp), color = c.lamp, maxLines = 1)
        Text(row.title, style = C2RTheme.type.rowTitle, color = c.onPanel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/**
 * The caller-ID strip for the next call. Its layout height is its natural height plus the header
 * offset (so the list moves up with it); in the graphics layer it fades, sinks a little and
 * shrinks towards its top edge as it collapses. Only layout / draw read the offset.
 */
@Composable
private fun CollapsingStrip(
    header: CollapsingHeaderState,
    row: TimelineRow,
    now: Instant,
    zone: ZoneId,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val key = UpNextShared.strip(row.occurrenceId)
    val status = if (row.isSnoozed) {
        stringResource(R.string.upnext_snoozed_status, untilText(Relative.until(row.fireAt, now, zone)))
    } else {
        stringResource(R.string.upnext_next_call, untilText(Relative.until(row.fireAt, now, zone)))
    }
    Box(
        modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                header.maxCollapsePx = placeable.height.toFloat()
                val visible = (placeable.height + header.offsetPx).roundToInt().coerceAtLeast(0)
                layout(placeable.width, visible) {
                    placeable.placeRelative(0, (header.offsetPx / 2f).roundToInt())
                }
            }
            // The strip slides up under the top bar's edge rather than over the title.
            .clipToBounds()
            .graphicsLayer {
                val f = header.fraction
                alpha = (1f - f * 1.25f).coerceIn(0f, 1f)
                val s = 1f - 0.06f * f
                scaleX = s
                scaleY = s
            },
    ) {
        Column(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)
                .pressScale(interaction, pressedScale = 0.985f)
                .sharedBoundsIfAvailable(SharedKeys.callerStrip(key))
                .fillMaxWidth()
                .background(c.panel, C2RTheme.shapes.strip)
                .clickable(interactionSource = interaction, indication = null, onClickLabel = row.title, onClick = onClick)
                .testTag(UpNextTags.STRIP)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LampDot(lit = true, halo = true, pulse = row.isSnoozed)
                    Text(status, style = C2RTheme.type.caption, color = c.onPanelMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SourceChip(row.source, color = c.onPanelMuted)
            }
            MonoTime(
                timeParts(row.fireAt),
                style = C2RTheme.type.monoLarge,
                modifier = Modifier.sharedTextIfAvailable(SharedKeys.time(key)),
            )
            Text(
                row.title,
                style = C2RTheme.type.cardTitle.copy(fontSize = 20.sp),
                color = c.onPanel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedTextIfAvailable(SharedKeys.title(key)),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Timeline rows
// ---------------------------------------------------------------------------------------------

@Composable
private fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = C2RTheme.type.section,
        color = C2RTheme.colors.ink,
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** The secondary line: "Google Calendar", "Habit, daily", "Google Tasks, no time set". */
@Composable
private fun cadenceDetail(row: TimelineRow): String? = when (val cadence = row.cadence) {
    RowCadence.Once -> null
    RowCadence.NoTimeSet -> stringResource(R.string.cadence_no_time)
    is RowCadence.Habit -> habitCadenceText(cadence.days, cadence.intervalWeeks)
    is RowCadence.Yearly -> if (cadence.dayBefore) stringResource(R.string.cadence_day_before) else null
}

@Composable
private fun TimelineRowItem(
    row: TimelineRow,
    showDate: Boolean,
    connector: Boolean,
    zone: ZoneId,
    now: Instant,
    actions: UpNextActions,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val done = SwipeAction(stringResource(R.string.swipe_done), C2RIcons.Check, c.lamp, c.onLamp)
    val skip = SwipeAction(stringResource(R.string.swipe_skip), C2RIcons.Close, c.tint, c.ink)
    val content: @Composable (List<CustomAccessibilityAction>) -> Unit = { a11yActions ->
        RowBody(row, showDate, connector, zone, now, a11yActions) { actions.onOpen(row, UpNextShared.row(row.occurrenceId)) }
    }
    Box(modifier.testTag(UpNextTags.row(row.occurrenceId))) {
        if (row.isFinished) {
            content(emptyList())
        } else {
            SwipeActionRow(
                start = done,
                end = skip,
                onStart = { actions.onSwipe(row, SwipeKind.DONE) },
                onEnd = { actions.onSwipe(row, SwipeKind.SKIP) },
                content = content,
            )
        }
    }
}

@Composable
private fun RowBody(
    row: TimelineRow,
    showDate: Boolean,
    connector: Boolean,
    zone: ZoneId,
    now: Instant,
    accessibilityActions: List<CustomAccessibilityAction>,
    onClick: () -> Unit,
) {
    val c = C2RTheme.colors
    val key = UpNextShared.row(row.occurrenceId)
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The rail runs under the dot, so it follows the (growable) time column's measured width.
    var railX by remember { mutableFloatStateOf(with(density) { (12 + 70 + 14 + 10).dp.toPx() }) }
    val line = c.line
    val past = row.isFinished || row.fireAt.isBefore(now)
    val time = timeParts(row.fireAt)
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressTint(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = !row.isFinished, onClickLabel = row.title, onClick = onClick)
            // Swipe actions live on the node TalkBack focuses (the clickable row), not a wrapper.
            .semantics { if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions }
            .drawBehind {
                if (connector) {
                    // Rail to the next row's dot: from under this dot into the next row's padding.
                    val x = if (rtl) size.width - railX else railX
                    drawLine(line, Offset(x, 10.dp.toPx() + 24.dp.toPx()), Offset(x, size.height + 12.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
            }
            .padding(start = 12.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier
                .widthIn(min = 70.dp)
                .onSizeChanged { railX = with(density) { 12.dp.toPx() + it.width + (14 + 10).dp.toPx() } },
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                time.text,
                style = C2RTheme.type.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (past) c.muted else c.ink,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .sharedTextIfAvailable(SharedKeys.time(key)),
            )
            if (showDate) {
                Text(
                    dayMonth(row.fireAt.atZone(zone).toLocalDate(), currentLocale()),
                    style = C2RTheme.type.footnote,
                    color = c.muted,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
        RowDot(row, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                row.title,
                style = C2RTheme.type.rowTitle.copy(
                    fontSize = 17.sp,
                    textDecoration = if (row.isFinished) TextDecoration.LineThrough else null,
                ),
                color = if (row.isFinished) c.muted else c.ink,
                modifier = Modifier.sharedTextIfAvailable(SharedKeys.title(key)),
            )
            val detail = when {
                row.isSnoozed -> stringResource(R.string.row_snoozed)
                row.state == OccurrenceState.SKIPPED -> stringResource(R.string.row_skipped)
                else -> cadenceDetail(row)
            }
            SourceChip(row.source, detail = detail)
        }
    }
}

/** The timeline dot: lit lamp for the next call, a check for done, a ring for everything else. */
@Composable
private fun RowDot(row: TimelineRow, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    Box(modifier.size(20.dp), contentAlignment = Alignment.Center) {
        when {
            row.isFinished -> Box(
                Modifier
                    .size(20.dp)
                    .background(if (row.state == OccurrenceState.DONE) c.ink else c.muted, C2RTheme.shapes.pill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (row.state == OccurrenceState.DONE) C2RIcons.Check else C2RIcons.Minus,
                    contentDescription = null,
                    tint = c.surface,
                    modifier = Modifier.size(13.dp),
                )
            }
            row.isNext -> LampDot(lit = true, halo = true, size = 14.dp)
            else -> LampDot(lit = false, size = 14.dp, unlitColor = c.muted)
        }
    }
}

@Composable
private fun RingingRow(row: TimelineRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .pressScale(interaction)
            .fillMaxWidth()
            .background(c.panel, C2RTheme.shapes.card)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = stringResource(R.string.upnext_tap_to_answer), onClick = onClick)
            .testTag(UpNextTags.row(row.occurrenceId))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (!C2RTheme.motion.reduced) LampPulse(color = c.lamp, dotSize = 40.dp, maxScale = 1.45f)
            Box(Modifier.size(40.dp).background(c.lamp, C2RTheme.shapes.pill), contentAlignment = Alignment.Center) {
                Icon(C2RIcons.Phone, contentDescription = null, tint = c.onLamp, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = C2RTheme.type.rowTitle.copy(fontSize = 17.sp), color = c.onPanel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.upnext_tap_to_answer), style = C2RTheme.type.caption, color = c.onPanelMuted)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// FAB, pull indicator, empty state
// ---------------------------------------------------------------------------------------------

@Composable
private fun NewHabitFab(expanded: Boolean, onClick: () -> Unit) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val interaction = remember { MutableInteractionSource() }
    val shape = C2RTheme.shapes.box
    val label = stringResource(R.string.action_new_habit)
    Row(
        Modifier
            .pressScale(interaction, pressedScale = 0.94f)
            .shadow(8.dp, shape, ambientColor = c.panel, spotColor = c.panel)
            .height(56.dp)
            .background(c.ink, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .testTag(UpNextTags.FAB)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Collapsed, the icon carries the label; expanded, the text does.
        Icon(C2RIcons.Plus, contentDescription = if (expanded) null else label, tint = c.surface, modifier = Modifier.size(24.dp))
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally(motion.default(), expandFrom = Alignment.Start) + fadeIn(motion.fade()),
            exit = shrinkHorizontally(motion.default(), shrinkTowards = Alignment.Start) + fadeOut(motion.fade()),
        ) {
            Text(
                label,
                style = C2RTheme.type.button,
                color = c.surface,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            )
        }
    }
}

/** Pull-to-sync: a panel puck drops in with the pull; its lamp lights at the threshold and pulses while syncing. */
@Composable
private fun PullIndicator(state: PullToRefreshState, refreshing: Boolean, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    Box(
        modifier
            .padding(top = 8.dp)
            .graphicsLayer {
                val f = state.distanceFraction.coerceIn(0f, 1.4f)
                translationY = (f * 72.dp.toPx()) - 44.dp.toPx()
                alpha = (f * 1.6f).coerceIn(0f, 1f)
                val s = 0.7f + 0.3f * f.coerceAtMost(1f)
                scaleX = s
                scaleY = s
            }
            .size(40.dp)
            .shadow(6.dp, C2RTheme.shapes.pill)
            .background(c.panel, C2RTheme.shapes.pill)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        val armed by remember(state) { derivedStateOf { state.distanceFraction >= 1f } }
        LampDot(lit = armed || refreshing, pulse = refreshing, size = 12.dp, unlitColor = c.onPanelMuted)
    }
}

/** EmptyHome: a dark switchboard (no lamps lit, empty jacks), then what to do about it. */
@Composable
private fun EmptyHome(onNewHabit: () -> Unit, onCheckSources: () -> Unit, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    Column(modifier.testTag(UpNextTags.EMPTY)) {
        Column(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                .fillMaxWidth()
                .background(c.panel, C2RTheme.shapes.strip)
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LampDot(lit = false, unlitColor = c.onPanelMuted)
                Text(stringResource(R.string.empty_status), style = C2RTheme.type.caption, color = c.onPanelMuted)
            }
            Text(
                "--:--",
                style = C2RTheme.type.monoLarge.copy(fontSize = 56.sp, letterSpacing = 0.04.em),
                color = c.onPanel.copy(alpha = 0.35f),
                modifier = Modifier.clearAndSetSemantics { },
            )
            Jacks()
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.empty_title),
                style = C2RTheme.type.sheetTitle,
                color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.empty_body), style = C2RTheme.type.body, color = c.muted)
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(R.string.action_new_habit), onNewHabit, Modifier.fillMaxWidth(), icon = C2RIcons.Plus)
            PillButton(stringResource(R.string.empty_check_sources), onCheckSources, Modifier.fillMaxWidth(), style = PillStyle.Outline, icon = C2RIcons.Plug)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Five unlit lamps over empty jacks, the switchboard at rest. */
@Composable
private fun Jacks() {
    val c = C2RTheme.colors
    val lamp = c.onPanel.copy(alpha = 0.35f)
    val jack = c.onPanel.copy(alpha = 0.45f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics { },
    ) {
        val n = 5
        val lampR = 6.dp.toPx()
        val jackR = 17.dp.toPx()
        val stroke = 2.dp.toPx()
        val step = (size.width - jackR * 2) / (n - 1)
        for (i in 0 until n) {
            val x = jackR + step * i
            drawCircle(lamp, radius = lampR - stroke / 2f, center = Offset(x, lampR), style = Stroke(stroke))
            val jy = lampR * 2 + 10.dp.toPx() + jackR
            drawCircle(jack, radius = jackR - stroke / 2f, center = Offset(x, jy), style = Stroke(stroke))
            drawCircle(jack, radius = 4.dp.toPx(), center = Offset(x, jy))
        }
    }
}
