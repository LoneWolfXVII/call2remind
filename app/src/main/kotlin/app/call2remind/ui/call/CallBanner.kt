package app.call2remind.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.call2remind.R
import app.call2remind.core.model.SourceType
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.LampPulse
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.SourceChip
import app.call2remind.ui.theme.C2RTheme

/**
 * The in-app variant of an incoming call (the HeadsUp design): a panel card pinned to the top of
 * the app with Snooze / Answer. For `RingMode.IN_APP_OVERLAY`, once the ringing service exposes a
 * hook for the foreground app to show it (today it launches the full-screen activity instead).
 */
@Composable
fun CallBanner(
    title: String,
    source: SourceType?,
    detail: String?,
    onAnswer: () -> Unit,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier,
    canSnooze: Boolean = true,
) {
    val c = C2RTheme.colors
    val shape = RoundedCornerShape(26.dp)
    val paneLabel = stringResource(R.string.call_banner_pane, title)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = Color(0x5914201A), spotColor = Color(0x5914201A))
            .clip(shape)
            .background(c.panel)
            .semantics {
                paneTitle = paneLabel
                liveRegion = LiveRegionMode.Assertive
            }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                LampPulse(color = c.lamp, dotSize = 44.dp, maxScale = 1.5f)
                Box(
                    Modifier
                        .size(44.dp)
                        .background(c.lamp, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(C2RIcons.Phone, contentDescription = null, tint = c.onLamp, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.call_banner_now), style = C2RTheme.type.footnote, color = c.onPanelMuted)
                Text(
                    title,
                    style = C2RTheme.type.cardTitle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = c.onPanel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (source != null) {
                    SourceChip(source, detail = detail, color = c.onPanelMuted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canSnooze) {
                PillButton(
                    stringResource(R.string.action_snooze),
                    onSnooze,
                    Modifier.weight(1f),
                    style = PillStyle.OnPanelOutline,
                    icon = C2RIcons.Snooze,
                    height = 48.dp,
                )
            }
            PillButton(
                stringResource(R.string.action_answer),
                onAnswer,
                Modifier.weight(1f),
                style = PillStyle.Lamp,
                icon = C2RIcons.Phone,
                height = 48.dp,
                textStyle = C2RTheme.type.button.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
