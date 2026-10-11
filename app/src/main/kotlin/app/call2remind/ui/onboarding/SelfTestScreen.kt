package app.call2remind.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.R
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.LampDot
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.RollingText
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.format.TimeFormat
import app.call2remind.ui.format.timeParts
import app.call2remind.ui.theme.C2RTheme

@Composable
fun SelfTestStep(
    viewModel: SelfTestViewModel,
    onFinish: () -> Unit,
    onReviewPermissions: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(R.string.selftest_reminder_title)
    SelfTestScreen(
        state = state,
        onStart = { viewModel.start(title) },
        onCancel = viewModel::cancel,
        onFinish = {
            viewModel.onLeave()
            onFinish()
        },
        onReviewPermissions = {
            viewModel.cancel()
            onReviewPermissions()
        },
        onBack = onBack,
    )
}

/**
 * Self-test: "Ring me in 1 minute" → a panel with the countdown in big rolling JetBrains Mono →
 * "It rang" (finish) or "It didn't ring" (review permissions / try again).
 */
@Composable
fun SelfTestScreen(
    state: SelfTestState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
    onReviewPermissions: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val motion = C2RTheme.motion
    val headline = stringResource(
        when (phase) {
            SelfTestPhase.IDLE, SelfTestPhase.STARTING -> R.string.selftest_idle_headline
            SelfTestPhase.SCHEDULED -> R.string.selftest_scheduled_headline
            SelfTestPhase.RANG -> R.string.selftest_rang_headline
            SelfTestPhase.LATE -> R.string.selftest_late_headline
        },
    )
    val body = when (phase) {
        SelfTestPhase.IDLE, SelfTestPhase.STARTING -> stringResource(R.string.selftest_idle_body)
        SelfTestPhase.SCHEDULED -> null
        SelfTestPhase.RANG -> stringResource(R.string.selftest_rang_body)
        SelfTestPhase.LATE -> stringResource(R.string.selftest_late_body)
    }
    OnboardingScaffold(
        step = 4,
        headline = headline,
        body = body,
        onBack = onBack,
        modifier = modifier,
        footer = {
            when (phase) {
                SelfTestPhase.IDLE, SelfTestPhase.STARTING -> {
                    PillButton(
                        stringResource(R.string.selftest_start),
                        onStart,
                        Modifier.fillMaxWidth(),
                        icon = C2RIcons.Phone,
                        height = 60.dp,
                        enabled = phase == SelfTestPhase.IDLE,
                    )
                    PillButton(stringResource(R.string.selftest_skip), onFinish, Modifier.fillMaxWidth(), style = PillStyle.Ghost, height = 48.dp)
                }
                SelfTestPhase.SCHEDULED -> {
                    PillButton(stringResource(R.string.selftest_cancel), onCancel, Modifier.fillMaxWidth(), style = PillStyle.Outline, height = 56.dp)
                    PillButton(stringResource(R.string.selftest_skip), onFinish, Modifier.fillMaxWidth(), style = PillStyle.Ghost, height = 48.dp)
                }
                SelfTestPhase.RANG -> {
                    PillButton(stringResource(R.string.selftest_finish), onFinish, Modifier.fillMaxWidth(), style = PillStyle.Primary, height = 60.dp)
                    PillButton(stringResource(R.string.selftest_didnt_ring), onReviewPermissions, Modifier.fillMaxWidth(), style = PillStyle.Ghost, height = 48.dp)
                }
                SelfTestPhase.LATE -> {
                    PillButton(stringResource(R.string.selftest_review), onReviewPermissions, Modifier.fillMaxWidth(), style = PillStyle.Primary, height = 60.dp)
                    PillButton(stringResource(R.string.selftest_finish_anyway), onFinish, Modifier.fillMaxWidth(), style = PillStyle.Ghost, height = 48.dp)
                }
            }
        },
    ) {
        AnimatedContent(
            targetState = phase == SelfTestPhase.SCHEDULED,
            transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.fade()) },
            label = "selfTestBody",
        ) { scheduled ->
            if (scheduled) {
                Column {
                    CountdownPanel(state)
                    ExpectList()
                }
            } else {
                ExpectList()
            }
        }
    }
}

@Composable
private fun CountdownPanel(state: SelfTestState) {
    val c = C2RTheme.colors
    val fireAt = state.fireAt
    val ringsAt = fireAt?.let { timeParts(it).text }.orEmpty()
    val remaining = TimeFormat.minutesSeconds(state.remaining)
    Column(
        Modifier
            .padding(start = 24.dp, end = 24.dp, top = 24.dp)
            .fillMaxWidth()
            .staggeredEntrance(0, distance = 24.dp)
            .clip(C2RTheme.shapes.panel)
            .background(c.panel)
            .padding(horizontal = 24.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LampDot(lit = true, halo = true, pulse = true)
            Text(stringResource(R.string.selftest_rings_at, ringsAt), style = C2RTheme.type.caption, color = c.onPanelMuted)
        }
        RollingText(
            text = remaining,
            style = C2RTheme.type.monoHero,
            color = c.lamp,
            contentDescription = stringResource(R.string.selftest_countdown_cd, remaining),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .border(1.5.dp, c.onPanelOutline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(C2RIcons.Lock, contentDescription = null, tint = c.onPanel, modifier = Modifier.size(20.dp))
            }
            Text(stringResource(R.string.selftest_lock_hint), style = C2RTheme.type.body, color = c.onPanel)
        }
    }
}

@Composable
private fun ExpectList() {
    val c = C2RTheme.colors
    Column(
        Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.selftest_expect_title),
            style = C2RTheme.type.rowTitle.copy(fontWeight = FontWeight.Bold),
            color = c.ink,
        )
        listOf(R.string.selftest_expect_1, R.string.selftest_expect_2, R.string.selftest_expect_3).forEachIndexed { i, res ->
            Row(
                Modifier.staggeredEntrance(i + 1),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .border(2.dp, c.ink, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(c.ink, CircleShape),
                    )
                }
                Text(stringResource(res), style = C2RTheme.type.body.copy(lineHeight = C2RTheme.type.caption.lineHeight), color = c.ink)
            }
        }
    }
}
