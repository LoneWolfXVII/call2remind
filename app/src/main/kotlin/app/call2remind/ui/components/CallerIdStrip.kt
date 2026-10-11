package app.call2remind.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.call2remind.core.model.SourceType
import app.call2remind.ui.format.TimeParts
import app.call2remind.ui.theme.C2RTheme

/**
 * A big JetBrains Mono clock time: digits at [style], the meridiem at ~0.42× on the same
 * baseline. Read by TalkBack as one phrase.
 */
@Composable
fun MonoTime(
    time: TimeParts,
    modifier: Modifier = Modifier,
    style: TextStyle = C2RTheme.type.monoCall,
    color: Color = C2RTheme.colors.lamp,
) {
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = time.text },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(time.digits, style = style, color = color, modifier = Modifier.alignByBaseline())
        time.meridiem?.let {
            val small = style.copy(fontSize = (style.fontSize.value * MERIDIEM_RATIO).sp, letterSpacing = style.letterSpacing)
            Text(it, style = small, color = color, modifier = Modifier.alignByBaseline())
        }
    }
}

private const val MERIDIEM_RATIO = 0.42f

/**
 * The caller-ID strip: a panel card with a lamp status line, the source, a big mono time and the
 * reminder title. Used by Welcome (demo call), Home "next call" (Phase 2) and the self-test.
 *
 * @param status the lamp line ("Incoming reminder", "Next call in 20 min").
 * @param footer optional content under the title (buttons on the Welcome demo).
 */
@Composable
fun CallerIdStrip(
    status: String,
    time: TimeParts,
    title: String,
    modifier: Modifier = Modifier,
    source: SourceType? = null,
    sourceBelowTitle: Boolean = false,
    shape: Shape = C2RTheme.shapes.strip,
    timeStyle: TextStyle = C2RTheme.type.monoLarge,
    titleStyle: TextStyle = C2RTheme.type.cardTitle,
    pulse: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    val c = C2RTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.panel, shape)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LampDot(lit = true, halo = true, pulse = pulse)
                Text(status, style = C2RTheme.type.caption, color = c.onPanelMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (source != null && !sourceBelowTitle) {
                SourceChip(source, color = c.onPanelMuted)
            }
        }
        MonoTime(time, style = timeStyle)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = titleStyle, color = c.onPanel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (source != null && sourceBelowTitle) {
                SourceChip(source, color = c.onPanelMuted)
            }
        }
        footer?.invoke()
    }
}
