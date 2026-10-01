package app.call2remind.ui.call

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.call2remind.R
import app.call2remind.core.model.SourceType
import app.call2remind.ringing.CallUiState
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.LampDot
import app.call2remind.ui.components.MonoTime
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.SourceChip
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.format.TimeFormat
import app.call2remind.ui.format.currentLocale
import app.call2remind.ui.format.snoozeButtonText
import app.call2remind.ui.format.timeParts
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Everything the call screen can ask for. */
@Immutable
data class CallActions(
    val onAnswer: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onSnooze: (Duration) -> Unit = {},
    val onDone: () -> Unit = {},
    /** `null` when the reminder's source has no app to open (habits). */
    val onOpenSource: (() -> Unit)? = null,
    val onReadAgain: () -> Unit = {},
    val onStopVoice: () -> Unit = {},
)

/**
 * The full-screen call: Incoming → (plug in) → Answered, with the snooze sheet over either.
 *
 * The answered panel grows out of the socket as a circular reveal (animated bounds on the precise
 * spring), with a lamp-coloured wavefront at its edge; its content then staggers in. If the call
 * was answered elsewhere (notification action) the reveal starts from the bottom centre.
 */
@Composable
fun CallScreen(
    state: CallUiState,
    presentation: CallPresentation,
    speech: SpeechProgress,
    actions: CallActions,
    modifier: Modifier = Modifier,
    now: () -> Instant = Instant::now,
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    var answered by rememberSaveable { mutableStateOf(state.answered) }
    LaunchedEffect(state.answered) { if (state.answered) answered = true }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var socket by remember { mutableStateOf(Offset.Unspecified) }
    val reveal = remember { Animatable(if (answered) 1f else 0f) }
    val revealed by remember { derivedStateOf { reveal.value >= 1f } }
    LaunchedEffect(answered) {
        if (answered && reveal.value < 1f) {
            delay(REVEAL_DELAY_MS) // let the plug seat first
            reveal.animateTo(1f, motion.precise(0.0005f))
        }
    }
    val sheet by animateFloatAsState(if (sheetOpen) 1f else 0f, motion.default(0.001f), label = "sheetDepth")
    BackHandler(enabled = sheetOpen) { sheetOpen = false }

    Box(
        modifier
            .fillMaxSize()
            .background(c.panel),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // The call recedes while the sheet is up (the mockup dims it to 45 %).
                    val s = sheet.coerceIn(0f, 1f)
                    alpha = 1f - 0.55f * s
                    scaleX = 1f - 0.03f * s
                    scaleY = 1f - 0.03f * s
                },
        ) {
            if (!revealed) {
                IncomingPane(
                    state = state,
                    presentation = presentation,
                    onAnswer = {
                        answered = true
                        actions.onAnswer()
                    },
                    onDecline = actions.onDecline,
                    onOpenSnooze = { sheetOpen = true },
                    onSocketCenter = { socket = it },
                    enabled = !answered,
                )
            }
            if (answered) {
                AnsweredPane(
                    state = state,
                    presentation = presentation,
                    speech = speech,
                    actions = actions,
                    onOpenSnooze = { sheetOpen = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val r = reveal.value
                            if (r < 1f) {
                                clip = true
                                shape = CircleReveal(socket, r)
                            }
                        }
                        .background(c.panel),
                )
                RevealWavefront(socket = { socket }, progress = { reveal.value })
            }
        }

        SheetHost(open = sheetOpen, onDismiss = { sheetOpen = false }, label = stringResource(R.string.snooze_pane)) {
            SnoozeSheetContent(
                presentation = presentation,
                answered = answered,
                now = now(),
                onSnooze = {
                    sheetOpen = false
                    actions.onSnooze(it)
                },
                onBack = { sheetOpen = false },
            )
        }
    }
}

private const val REVEAL_DELAY_MS = 140L

/** Circle centred on [center] (bottom centre if unspecified) whose radius reaches the far corner at progress 1. */
private class CircleReveal(private val center: Offset, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val origin = revealOrigin(center, size, density)
        val radius = revealRadius(origin, size) * progress
        return Outline.Generic(Path().apply { addOval(Rect(origin, radius)) })
    }
}

private fun revealOrigin(center: Offset, size: Size, density: Density): Offset =
    if (center.isSpecified) center else Offset(size.width / 2f, size.height - with(density) { 120.dp.toPx() })

private fun revealRadius(origin: Offset, size: Size): Float =
    hypot(max(origin.x, size.width - origin.x), max(origin.y, size.height - origin.y))

/** The lamp-coloured ring riding the reveal's edge, fading as it reaches the corners. */
@Composable
private fun BoxScope.RevealWavefront(socket: () -> Offset, progress: () -> Float) {
    val lamp = C2RTheme.colors.lamp
    Canvas(Modifier.matchParentSize()) {
        val p = progress()
        if (p <= 0f || p >= 1f) return@Canvas
        val origin = revealOrigin(socket(), size, this)
        val radius = revealRadius(origin, size) * p
        drawCircle(lamp.copy(alpha = (1f - p) * 0.9f), radius = radius, center = origin, style = Stroke(3.dp.toPx() * (1f - p) + 1f))
    }
}

@Composable
private fun CallHeader(right: String) {
    val c = C2RTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LampDot(lit = true, halo = true)
            Text(stringResource(R.string.app_name), style = C2RTheme.type.caption, color = c.onPanelMuted)
        }
        Text(right, style = C2RTheme.type.caption, color = c.onPanelMuted)
    }
}

@Composable
private fun IncomingPane(
    state: CallUiState,
    presentation: CallPresentation,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onOpenSnooze: () -> Unit,
    onSocketCenter: (Offset) -> Unit,
    enabled: Boolean,
) {
    val c = C2RTheme.colors
    val haptics = rememberHaptics()
    val title = state.title.ifBlank { stringResource(R.string.reminder_fallback_title) }
    val time = presentation.plannedAt ?: state.fireAt ?: Instant.now()
    val today = TimeFormat.shortDate(Instant.now(), ZoneId.systemDefault(), currentLocale())
    val subtitle = when {
        presentation.ringBacks > 0 -> stringResource(R.string.call_ringback, presentation.ringBacks, presentation.maxRingBacks)
        else -> state.notes?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        CallHeader(today)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 48.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Box(
                Modifier
                    .staggeredEntrance(0)
                    .clip(C2RTheme.shapes.box)
                    .background(Color.Black.copy(alpha = 0.22f))
                    .border(1.dp, c.onPanel.copy(alpha = 0.14f), C2RTheme.shapes.box)
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
            ) {
                MonoTime(timeParts(time), style = C2RTheme.type.monoCall)
            }
            Text(
                title,
                style = C2RTheme.type.callTitle,
                color = c.onPanel,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .staggeredEntrance(1)
                    .semantics { heading() },
            )
            Column(Modifier.staggeredEntrance(2), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.sourceType?.let {
                    SourceChip(it, color = c.onPanel, style = C2RTheme.type.body.copy(lineHeight = 21.sp))
                }
                if (subtitle != null) {
                    Text(subtitle, style = C2RTheme.type.body, color = c.onPanelMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PlugSlider(
                label = stringResource(R.string.call_slide_to_answer),
                answerLabel = stringResource(R.string.action_answer),
                declineLabel = stringResource(R.string.action_decline),
                onAnswer = onAnswer,
                onDecline = {
                    haptics.perform(Haptic.Reject)
                    onDecline()
                },
                enabled = enabled,
                onSocketCenter = onSocketCenter,
                modifier = Modifier.staggeredEntrance(3),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(4),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (presentation.canSnooze) {
                    PillButton(
                        text = snoozeButtonText(presentation.defaultSnooze),
                        onClick = {
                            haptics.perform(Haptic.Reject)
                            onDecline()
                        },
                        onLongClick = {
                            haptics.perform(Haptic.SegmentTick)
                            onOpenSnooze()
                        },
                        onLongClickLabel = stringResource(R.string.call_snooze_choose),
                        style = PillStyle.OnPanelOutline,
                        icon = C2RIcons.Snooze,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                    )
                    Text(stringResource(R.string.call_snooze_hold_hint), style = C2RTheme.type.caption, color = c.onPanelMuted)
                } else {
                    PillButton(
                        text = stringResource(R.string.action_decline),
                        onClick = {
                            haptics.perform(Haptic.Reject)
                            onDecline()
                        },
                        style = PillStyle.OnPanelOutline,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                    )
                    Text(stringResource(R.string.call_decline_to_missed), style = C2RTheme.type.caption, color = c.onPanelMuted)
                }
            }
        }
    }
}

@Composable
private fun AnsweredPane(
    state: CallUiState,
    presentation: CallPresentation,
    speech: SpeechProgress,
    actions: CallActions,
    onOpenSnooze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val haptics = rememberHaptics()
    val title = state.title.ifBlank { stringResource(R.string.reminder_fallback_title) }
    val voiceOn = presentation.spokenText != null
    val transcript = presentation.transcript.ifBlank { title }
    val spoken = rememberSpokenChars(transcript, voiceOn, speech)
    val length = transcript.length.coerceAtLeast(1)
    val answeredAt = remember(presentation.answeredAt) { presentation.answeredAt ?: Instant.now() }
    val elapsed by produceState(TimeFormat.minutesSeconds(Duration.ZERO), answeredAt) {
        // An "infinite animation" tick (paused by tests and when animations are off-screen),
        // then sleep until the next whole second.
        while (true) {
            withInfiniteAnimationFrameMillis { }
            val d = Duration.between(answeredAt, Instant.now())
            value = TimeFormat.minutesSeconds(d)
            delay(1_000L - (d.toMillis() % 1_000L).coerceIn(0L, 999L))
        }
    }
    val headerRight = when {
        speech.speaking -> stringResource(R.string.call_reading_aloud)
        !voiceOn -> stringResource(R.string.call_voice_off)
        else -> ""
    }
    Column(modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        CallHeader(headerRight)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.staggeredEntrance(0), verticalAlignment = Alignment.CenterVertically) {
                    state.sourceType?.let {
                        SourceChip(it, color = c.onPanelMuted, style = C2RTheme.type.caption.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                    } ?: Spacer(Modifier.weight(1f))
                    Text(elapsed, style = C2RTheme.type.monoTimer, color = c.onPanel)
                }
                Text(
                    title,
                    style = C2RTheme.type.callTitleSmall,
                    color = c.onPanel,
                    modifier = Modifier
                        .staggeredEntrance(1)
                        .semantics { heading() },
                )
            }
            Waveform(
                progress = { spoken.value / length },
                speaking = speech.speaking,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp),
            )
            Transcript(
                text = transcript,
                spokenChars = { spoken.value },
                modifier = Modifier
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)
                    .staggeredEntrance(2),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PillButton(
                text = stringResource(R.string.action_done),
                onClick = {
                    haptics.perform(Haptic.Confirm)
                    actions.onDone()
                },
                style = PillStyle.Lamp,
                icon = C2RIcons.Check,
                height = 64.dp,
                textStyle = C2RTheme.type.button.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(3),
            )
            Row(
                Modifier.staggeredEntrance(4),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (presentation.canSnooze) {
                    PillButton(
                        text = stringResource(R.string.action_snooze),
                        onClick = onOpenSnooze,
                        style = PillStyle.OnPanelOutline,
                        icon = C2RIcons.Snooze,
                        modifier = Modifier.weight(1f),
                    )
                }
                val open = actions.onOpenSource
                val source = state.sourceType
                if (open != null && source != null && source != SourceType.HABIT) {
                    PillButton(
                        text = stringResource(R.string.call_open_in, stringResource(source.appNameRes)),
                        onClick = open,
                        style = PillStyle.OnPanelOutline,
                        icon = C2RIcons.OpenExternal,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (voiceOn) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(5),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    val small = C2RTheme.type.button.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    PillButton(
                        text = stringResource(R.string.call_read_again),
                        onClick = actions.onReadAgain,
                        style = PillStyle.OnPanelGhost,
                        icon = C2RIcons.Replay,
                        height = 48.dp,
                        textStyle = small,
                    )
                    PillButton(
                        text = stringResource(R.string.call_stop_voice),
                        onClick = actions.onStopVoice,
                        style = PillStyle.OnPanelGhost,
                        icon = C2RIcons.Speaker,
                        height = 48.dp,
                        textStyle = small,
                        enabled = speech.speaking,
                    )
                }
            }
        }
    }
}

/** Short app name for "Open in …". */
private val SourceType.appNameRes: Int
    get() = when (this) {
        SourceType.CALENDAR -> R.string.app_calendar
        SourceType.GOOGLE_TASKS -> R.string.app_tasks
        SourceType.SAMSUNG_REMINDER -> R.string.app_reminders
        SourceType.MS_TODO -> R.string.app_todo
        SourceType.BIRTHDAY -> R.string.app_contacts
        SourceType.HABIT -> R.string.app_name
    }

/**
 * Bottom sheet container: slides up on the default spring (a touch of overshoot), follows a
 * downward drag and dismisses past 30 % or on a fling; tapping above it dismisses.
 */
@Composable
private fun BoxScope.SheetHost(open: Boolean, onDismiss: () -> Unit, label: String, content: @Composable () -> Unit) {
    val motion = C2RTheme.motion
    val c = C2RTheme.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val hidden = remember { Animatable(1f) }
    var height by remember { mutableIntStateOf(0) }
    LaunchedEffect(open) { hidden.animateTo(if (open) 0f else 1f, motion.default(0.0005f)) }
    val visible by remember { derivedStateOf { hidden.value < 1f } }
    if (!open && !visible) return
    val flingPx = with(density) { 1_200.dp.toPx() }
    val currentDismiss by rememberUpdatedState(onDismiss)

    // Tap-to-dismiss area above the sheet; it never overlaps the sheet, so taps on the sheet's
    // own controls cannot fall through to it.
    Column(Modifier.matchParentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(interactionSource = null, indication = null, onClickLabel = stringResource(R.string.snooze_back_to_call)) { onDismiss() },
        )
        Column(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .onSizeChanged { height = it.height }
                .offset { IntOffset(0, (hidden.value * height).roundToInt()) }
                // Shape drawn, not clipped: nothing overflows, and a clip layer with top-only
                // corners would need path-based hit testing for every touch on the sheet.
                .background(c.surface, C2RTheme.shapes.sheet)
                .semantics { paneTitle = label }
                .testTag(CallTags.SNOOZE_SHEET)
                .pointerInput(Unit) {
                    // Follows a downward drag; taps (no touch slop) pass through to the controls.
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onVerticalDrag = { change, delta ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (height > 0) scope.launch { hidden.snapTo((hidden.value + delta / height).coerceIn(-0.03f, 1f)) }
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity().y
                            if (hidden.value > DISMISS_FRACTION || velocity > flingPx) {
                                currentDismiss()
                            } else {
                                scope.launch {
                                    hidden.animateTo(0f, motion.default(0.0005f), initialVelocity = if (height > 0) velocity / height else 0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { hidden.animateTo(0f, motion.default(0.0005f)) } },
                    )
                }
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            content()
        }
    }
}

private const val DISMISS_FRACTION = 0.3f
