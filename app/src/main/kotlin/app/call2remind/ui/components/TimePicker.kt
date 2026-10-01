package app.call2remind.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.call2remind.R
import app.call2remind.ui.format.currentLocale
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Test tags of the time picker wheels. */
object TimePickerTags {
    const val HOURS = "time_wheel_hours"
    const val MINUTES = "time_wheel_minutes"
    const val MERIDIEM = "time_wheel_meridiem"
    const val CONFIRM = "time_picker_confirm"
}

/** Wheel index ↔ [LocalTime] mapping, pure for tests. */
object TimeWheelMath {
    fun hourIndex(time: LocalTime, is24Hour: Boolean): Int = if (is24Hour) time.hour else time.hour % 12

    fun hourLabel(index: Int, is24Hour: Boolean): String =
        if (is24Hour) "%02d".format(Locale.ROOT, index) else (if (index == 0) 12 else index).toString()

    fun meridiemIndex(time: LocalTime): Int = if (time.hour >= 12) 1 else 0

    fun compose(hourIndex: Int, minute: Int, meridiemIndex: Int, is24Hour: Boolean): LocalTime {
        val hour = if (is24Hour) hourIndex else hourIndex % 12 + if (meridiemIndex == 1) 12 else 0
        return LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    }
}

/**
 * The exchange time picker: a panel box with mono wheels (hours, minutes, AM/PM). The centred
 * value is lamp-lit; neighbours fade and shrink with their distance from the centre as they
 * scroll. Wheels snap to a value, tick (haptic) on every detent, and report each new time
 * immediately. TalkBack gets "earlier / later" actions on each wheel.
 */
@Composable
fun MonoTimePicker(
    time: LocalTime,
    onTimeChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    is24Hour: Boolean = DateFormat.is24HourFormat(LocalContext.current),
) {
    val c = C2RTheme.colors
    val current by rememberUpdatedState(time)
    val change by rememberUpdatedState(onTimeChange)
    val locale = currentLocale()
    val meridiems = remember(locale) {
        listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)).map { DateTimeFormatter.ofPattern("a", locale).format(it).uppercase(locale) }
    }
    val band = c.onPanel.copy(alpha = 0.08f)
    Row(
        modifier
            .fillMaxWidth()
            .background(c.panel, C2RTheme.shapes.strip)
            .drawBehind {
                val bandHeight = ITEM_HEIGHT.toPx()
                drawRoundRect(
                    color = band,
                    topLeft = Offset(12.dp.toPx(), (size.height - bandHeight) / 2f),
                    size = Size(size.width - 24.dp.toPx(), bandHeight),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                )
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wheel(
            count = if (is24Hour) 24 else 12,
            selected = TimeWheelMath.hourIndex(time, is24Hour),
            label = { TimeWheelMath.hourLabel(it, is24Hour) },
            onSelect = { change(TimeWheelMath.compose(it, current.minute, TimeWheelMath.meridiemIndex(current), is24Hour)) },
            width = 84.dp,
            tag = TimePickerTags.HOURS,
            description = stringResource(R.string.time_picker_hours),
        )
        Text(":", style = C2RTheme.type.monoLarge.copy(fontSize = 40.sp), color = c.lamp, modifier = Modifier.padding(bottom = 4.dp))
        Wheel(
            count = 60,
            selected = time.minute,
            label = { "%02d".format(Locale.ROOT, it) },
            onSelect = { change(TimeWheelMath.compose(TimeWheelMath.hourIndex(current, is24Hour), it, TimeWheelMath.meridiemIndex(current), is24Hour)) },
            width = 84.dp,
            tag = TimePickerTags.MINUTES,
            description = stringResource(R.string.time_picker_minutes),
        )
        if (!is24Hour) {
            Wheel(
                count = 2,
                selected = TimeWheelMath.meridiemIndex(time),
                label = { meridiems[it] },
                onSelect = { change(TimeWheelMath.compose(TimeWheelMath.hourIndex(current, false), current.minute, it, false)) },
                width = 76.dp,
                tag = TimePickerTags.MERIDIEM,
                description = stringResource(R.string.time_picker_meridiem),
                small = true,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Wheel(
    count: Int,
    selected: Int,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
    width: Dp,
    tag: String,
    description: String,
    small: Boolean = false,
) {
    val c = C2RTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val itemPx = with(LocalDensity.current) { ITEM_HEIGHT.toPx() }
    val state: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, count - 1))
    val center by remember(count) {
        derivedStateOf {
            val extra = if (state.firstVisibleItemScrollOffset > itemPx / 2f) 1 else 0
            (state.firstVisibleItemIndex + extra).coerceIn(0, count - 1)
        }
    }
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(state, count) {
        snapshotFlow { center }
            .distinctUntilChanged()
            .drop(1)
            .collect {
                haptics.perform(Haptic.SegmentTick)
                select(it)
            }
    }
    // Outside changes (a template, a reset) move the wheel, unless the user is spinning it.
    LaunchedEffect(selected) {
        if (!state.isScrollInProgress && center != selected) state.animateScrollToItem(selected.coerceIn(0, count - 1))
    }
    val valueText = label(center)
    val earlier = stringResource(R.string.time_picker_earlier)
    val later = stringResource(R.string.time_picker_later)
    val style = if (small) C2RTheme.type.monoTimer else C2RTheme.type.monoLarge.copy(fontSize = 40.sp)
    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = ITEM_HEIGHT),
        modifier = Modifier
            .width(width)
            .height(ITEM_HEIGHT * 3)
            .testTag(tag)
            // One screen-reader stop per wheel (the visible numbers are cleared below), so the
            // earlier / later actions sit on the node TalkBack actually focuses.
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = valueText
                customActions = listOf(
                    CustomAccessibilityAction(earlier) {
                        scope.launch { state.animateScrollToItem((center - 1).coerceAtLeast(0)) }
                        true
                    },
                    CustomAccessibilityAction(later) {
                        scope.launch { state.animateScrollToItem((center + 1).coerceAtMost(count - 1)) }
                        true
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count) { index ->
            Box(
                Modifier
                    .height(ITEM_HEIGHT)
                    .fillMaxWidth()
                    .clearAndSetSemantics {}
                    .graphicsLayer {
                        val position = state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / itemPx
                        val distance = abs(index - position).coerceAtMost(1.6f)
                        val scale = 1f - 0.22f * distance
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - 0.62f * distance).coerceIn(0.12f, 1f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(index),
                    style = style,
                    color = if (index == center) c.lamp else c.onPanel,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Sheet body for picking a time: title, optional explanation, the [MonoTimePicker] and a confirm
 * button that names the result ("Ring at 9:30 AM").
 */
@Composable
fun TimePickerSheetContent(
    title: String,
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val c = C2RTheme.colors
    val haptics = rememberHaptics()
    var picked by rememberSaveable(initial) { mutableStateOf(initial) }
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val pickedText = remember(picked, is24Hour) { formatLocalTime(picked, is24Hour) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = C2RTheme.type.sheetTitle, color = c.ink, modifier = Modifier.semantics { heading() })
        if (body != null) Text(body, style = C2RTheme.type.body, color = c.muted)
        MonoTimePicker(time = picked, onTimeChange = { picked = it }, is24Hour = is24Hour)
        extra?.invoke()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PillButton(
                stringResource(R.string.time_picker_confirm, pickedText),
                onClick = {
                    haptics.perform(Haptic.Confirm)
                    onConfirm(picked)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TimePickerTags.CONFIRM),
                height = 56.dp,
            )
            PillButton(stringResource(R.string.action_cancel), onCancel, Modifier.fillMaxWidth(), style = PillStyle.Ghost, height = 48.dp)
        }
    }
}

/** "9:30 AM" / "21:30" for a wall-clock time. */
fun formatLocalTime(time: LocalTime, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
    if (is24Hour) {
        DateTimeFormatter.ofPattern("H:mm", locale).format(time)
    } else {
        DateTimeFormatter.ofPattern("h:mm", locale).format(time) + " " + DateTimeFormatter.ofPattern("a", locale).format(time).uppercase(locale)
    }

private val ITEM_HEIGHT = 56.dp
