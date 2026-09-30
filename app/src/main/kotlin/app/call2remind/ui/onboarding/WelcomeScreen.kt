package app.call2remind.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.call2remind.R
import app.call2remind.core.model.SourceType
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.CallerIdStrip
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.format.timeParts
import app.call2remind.ui.theme.C2RTheme
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Welcome: the product explained by showing it — a live caller-ID card (pulsing lamp) of the call
 * the user is about to set up, then one line of copy and "Get started".
 */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val demoTime = remember {
        ZonedDateTime.now(ZoneId.systemDefault()).with(LocalTime.of(9, 30)).toInstant()
    }
    val demoTitle = stringResource(R.string.welcome_demo_title)
    val demoParts = timeParts(demoTime)
    val demoDescription = stringResource(R.string.welcome_demo_cd, demoTitle, demoParts.text)
    Column(
        modifier
            .fillMaxSize()
            .background(c.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(C2RTheme.shapes.tileSmall)
                        .background(c.ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(C2RIcons.Phone, contentDescription = null, tint = c.lamp, modifier = Modifier.size(18.dp))
                }
                Text(
                    stringResource(R.string.app_name),
                    style = C2RTheme.type.rowTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    color = c.ink,
                )
            }
            CallerIdStrip(
                status = stringResource(R.string.welcome_demo_status),
                time = demoParts,
                title = demoTitle,
                source = SourceType.CALENDAR,
                sourceBelowTitle = true,
                shape = C2RTheme.shapes.panel,
                timeStyle = C2RTheme.type.monoLarge.copy(fontSize = 60.sp, lineHeight = 60.sp),
                pulse = true,
                modifier = Modifier
                    .padding(start = 24.dp, end = 24.dp, top = 36.dp)
                    .staggeredEntrance(0, distance = 24.dp)
                    .clearAndSetSemantics { contentDescription = demoDescription },
                footer = { DemoButtons() },
            )
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 36.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    stringResource(R.string.welcome_headline),
                    style = C2RTheme.type.display,
                    color = c.ink,
                    modifier = Modifier
                        .staggeredEntrance(1)
                        .semantics { heading() },
                )
                Text(
                    stringResource(R.string.welcome_body),
                    style = C2RTheme.type.lead,
                    color = c.muted,
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .staggeredEntrance(2),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PillButton(
                text = stringResource(R.string.welcome_cta),
                onClick = onGetStarted,
                style = PillStyle.Primary,
                height = 60.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.welcome_sources),
                style = C2RTheme.type.footnote,
                color = c.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The demo card's Snooze / Answer, drawn but inert (the card is an illustration). */
@Composable
private fun DemoButtons() {
    val c = C2RTheme.colors
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(C2RTheme.shapes.pill)
                .border(1.5.dp, c.onPanel.copy(alpha = 0.4f), C2RTheme.shapes.pill),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(C2RIcons.Snooze, contentDescription = null, tint = c.onPanel, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.action_snooze), style = C2RTheme.type.button.copy(fontSize = 15.sp), color = c.onPanel)
        }
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(C2RTheme.shapes.pill)
                .background(c.lamp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(C2RIcons.Phone, contentDescription = null, tint = c.onLamp, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.action_answer),
                style = C2RTheme.type.button.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = c.onLamp,
            )
        }
    }
}
