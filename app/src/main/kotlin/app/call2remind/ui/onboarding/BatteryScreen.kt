package app.call2remind.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.R
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.LampDot
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.system.SystemIntents
import app.call2remind.ui.theme.C2RTheme

/**
 * Battery step. Every phone: the battery-optimization exemption row. Samsung phones also get the
 * Device Care walkthrough ("Never sleeping apps"), with a deep link that tries the known Device
 * Care components and falls back to the app's settings.
 */
@Composable
fun BatteryStep(viewModel: OnboardingViewModel, onContinue: () -> Unit, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    BatteryScreen(
        unrestricted = state.setup.batteryUnrestricted,
        samsung = state.setup.isSamsung,
        onAllowBackground = { SystemIntents.launchFirst(context, SystemIntents.batteryOptimization(context)) },
        onOpenDeviceCare = { SystemIntents.launchFirst(context, SystemIntents.samsungDeviceCare(context)) },
        onContinue = {
            viewModel.onBatteryDone()
            onContinue()
        },
        onBack = onBack,
    )
}

@Composable
fun BatteryScreen(
    unrestricted: Boolean,
    samsung: Boolean,
    onAllowBackground: () -> Unit,
    onOpenDeviceCare: () -> Unit,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        step = 3,
        headline = stringResource(if (samsung) R.string.battery_samsung_headline else R.string.battery_headline),
        body = stringResource(if (samsung) R.string.battery_samsung_body else R.string.battery_body),
        onBack = onBack,
        modifier = modifier,
        footer = {
            if (samsung) {
                PillButton(
                    text = stringResource(R.string.battery_open_settings),
                    onClick = onOpenDeviceCare,
                    icon = C2RIcons.OpenExternal,
                    height = 60.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
                PillButton(
                    text = stringResource(R.string.battery_done),
                    onClick = onContinue,
                    style = PillStyle.Ghost,
                    height = 52.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (unrestricted) {
                PillButton(stringResource(R.string.action_continue), onContinue, Modifier.fillMaxWidth(), height = 60.dp)
            } else {
                PillButton(stringResource(R.string.battery_allow), onAllowBackground, Modifier.fillMaxWidth(), height = 60.dp)
                PillButton(stringResource(R.string.battery_not_now), onContinue, Modifier.fillMaxWidth(), style = PillStyle.Ghost, height = 52.dp)
            }
        },
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
            SetupRow(
                key = "BATTERY",
                icon = C2RIcons.Battery,
                title = stringResource(R.string.battery_row_title),
                subtitle = stringResource(R.string.battery_row_why),
                granted = unrestricted,
                onAllow = onAllowBackground,
                showDivider = samsung,
            )
        }
        if (samsung) {
            NeverSleepingIllustration(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp).staggeredEntrance(1))
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SamsungStep(1, stringResource(R.string.battery_step1), stringResource(R.string.battery_step1_hint), current = true, index = 2)
                SamsungStep(2, stringResource(R.string.battery_step2), stringResource(R.string.battery_step2_hint), current = false, index = 3)
                SamsungStep(3, stringResource(R.string.battery_step3), stringResource(R.string.battery_step3_hint), current = false, index = 4)
            }
        }
    }
}

/** What the target settings page looks like, so the user recognises it (decorative). */
@Composable
private fun NeverSleepingIllustration(modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val line = c.line
    Column(
        modifier
            .fillMaxWidth()
            .clip(C2RTheme.shapes.card)
            .background(c.surface)
            .clearAndSetSemantics { }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.battery_never_sleeping),
                style = C2RTheme.type.rowTitle.copy(fontSize = 15.sp),
                modifier = Modifier.weight(1f),
            )
            Icon(C2RIcons.Plus, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
        }
        Row(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(C2RTheme.shapes.tileSmall)
                    .background(c.ink),
                contentAlignment = Alignment.Center,
            ) {
                Icon(C2RIcons.Phone, contentDescription = null, tint = c.lamp, modifier = Modifier.size(18.dp))
            }
            Text(
                stringResource(R.string.app_name),
                style = C2RTheme.type.rowTitle.copy(fontSize = 15.sp),
                modifier = Modifier.weight(1f),
            )
            LampDot(lit = true, halo = true)
        }
    }
}

/** A numbered instruction (the numbers are a real sequence here). */
@Composable
private fun SamsungStep(number: Int, title: String, hint: String, current: Boolean, index: Int) {
    val c = C2RTheme.colors
    Row(
        Modifier.staggeredEntrance(index),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (current) c.ink else c.ground)
                .border(1.5.dp, c.ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = C2RTheme.type.button.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = if (current) c.surface else c.ink,
            )
        }
        Column(Modifier.padding(top = 4.dp)) {
            Text(title, style = C2RTheme.type.rowTitle, color = c.ink)
            Text(hint, style = C2RTheme.type.caption, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
