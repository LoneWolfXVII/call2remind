package app.call2remind.ui.call

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import app.call2remind.ui.theme.C2RMotion
import app.call2remind.ui.theme.C2RTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Bar heights (dp) from the Answered design. */
private val BAR_HEIGHTS = floatArrayOf(
    10f, 18f, 30f, 22f, 40f, 28f, 52f, 36f, 24f, 44f, 30f, 18f, 34f, 48f, 26f,
    14f, 22f, 36f, 28f, 18f, 12f, 20f, 30f, 16f, 10f, 14f, 22f, 12f, 8f, 12f,
)

/** Characters per second used when the TTS engine does not report word ranges (~170 wpm). */
internal const val ESTIMATED_CHARS_PER_SECOND = 14f

/** How long an answered call waits for speech to start before showing the full transcript. */
private const val SPEECH_GRACE_MS = 2_500L

/**
 * Works out how far through [text] the voice is, in characters (animated).
 *
 * With `onRangeStart` support ([SpeechProgress.rangeExposed]) it follows the engine; otherwise it
 * animates an estimate at [ESTIMATED_CHARS_PER_SECOND] from the moment speech started. When the
 * voice is off everything counts as read.
 */
@Composable
fun rememberSpokenChars(text: String, voiceOn: Boolean, speech: SpeechProgress): State<Float> {
    val motion = C2RTheme.motion
    val chars = remember(text) { Animatable(if (voiceOn) 0f else text.length.toFloat()) }
    val length = text.length.toFloat()
    LaunchedEffect(text, voiceOn, speech.generation, speech.rangeExposed, speech.finished, speech.speaking) {
        when {
            !voiceOn || speech.finished -> chars.animateTo(length, motion.precise())
            speech.rangeExposed -> Unit // follow ranges below
            speech.speaking -> {
                val remaining = (length - chars.value).coerceAtLeast(0f)
                chars.animateTo(length, tween(((remaining / ESTIMATED_CHARS_PER_SECOND) * 1000).toInt(), easing = LinearEasing))
            }
            else -> {
                // Not speaking (not started yet, stopped, or the engine failed): after a grace
                // period show the whole transcript rather than leave it dimmed.
                delay(SPEECH_GRACE_MS)
                chars.animateTo(length, motion.precise())
            }
        }
    }
    if (voiceOn && speech.rangeExposed && !speech.finished) {
        LaunchedEffect(speech.spokenUntil, speech.generation) {
            chars.animateTo(speech.spokenUntil.toFloat().coerceAtMost(length), spring(1f, Spring.StiffnessMediumLow))
        }
    }
    return chars.asState()
}

/**
 * The TTS waveform: 30 rounded bars. They grow in one after another (30 ms stagger, playful
 * spring) when the call connects; bars already "spoken" are lamp, the rest faint; while speaking,
 * the bars around the read head breathe.
 */
@Composable
fun Waveform(
    progress: () -> Float,
    speaking: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val grow = remember { List(BAR_HEIGHTS.size) { Animatable(if (motion.reduced) 1f else 0f) } }
    LaunchedEffect(Unit) {
        grow.forEachIndexed { i, bar ->
            launch {
                delay(i * C2RMotion.STAGGER_MS)
                bar.animateTo(1f, motion.playful())
            }
        }
    }
    val breathing = speaking && !motion.reduced
    val phase = if (breathing) {
        val transition = rememberInfiniteTransition(label = "wave")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(WAVE_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "wavePhase",
        )
    } else {
        null
    }
    val lit = c.lamp
    val unlit = c.onPanelFaint
    Canvas(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clearAndSetSemantics { },
    ) {
        val count = BAR_HEIGHTS.size
        val barWidth = 5.dp.toPx()
        val gap = (size.width - barWidth * count) / (count - 1)
        val p = progress().coerceIn(0f, 1f)
        val head = p * count
        val t = phase?.value ?: 0f
        for (i in 0 until count) {
            val g = grow[i].value
            if (g <= 0f) continue
            var h = BAR_HEIGHTS[i].dp.toPx() * g
            val distance = i - head
            if (phase != null && distance > -3f && distance < 2f) {
                h *= 0.725f + 0.275f * sin(t + i * 0.9f)
            }
            val color = when {
                i + 1 <= head -> lit
                i < head -> lerp(unlit, lit, head - i)
                else -> unlit
            }
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

private const val WAVE_PERIOD_MS = 1_100

/**
 * The transcript with word-by-word highlight: words already spoken in full on-panel colour, the
 * word being spoken fading up, the rest dimmed (42 %).
 */
@Composable
fun Transcript(text: String, spokenChars: () -> Float, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val words = remember(text) { wordRanges(text) }
    // Quantised so the text only re-lays-out when the highlight visibly changes.
    val step by remember(text) {
        derivedStateOf {
            val chars = spokenChars()
            val index = words.indexOfFirst { chars < it.last + 1 }
            if (index == -1) {
                words.size * 10
            } else {
                val w = words[index]
                val fraction = ((chars - w.first) / (w.last + 1 - w.first)).coerceIn(0f, 1f)
                index * 10 + (fraction * 9).toInt()
            }
        }
    }
    val annotated: AnnotatedString = remember(text, step, c) {
        val current = step / 10
        val fraction = (step % 10) / 9f
        buildAnnotatedString {
            append(text)
            words.forEachIndexed { i, range ->
                val color = when {
                    i < current -> c.onPanel
                    i == current -> lerp(c.onPanelDim, c.onPanel, fraction)
                    else -> c.onPanelDim
                }
                addStyle(SpanStyle(color = color), range.first, range.last + 1)
            }
        }
    }
    Text(
        text = annotated,
        style = C2RTheme.type.transcript,
        color = c.onPanelDim,
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
    )
}

/** Character ranges of the words in [text] (runs of non-whitespace). */
internal fun wordRanges(text: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var start = -1
    for (i in text.indices) {
        if (text[i].isWhitespace()) {
            if (start >= 0) out += start until i
            start = -1
        } else if (start < 0) {
            start = i
        }
    }
    if (start >= 0) out += start until text.length
    return out
}
