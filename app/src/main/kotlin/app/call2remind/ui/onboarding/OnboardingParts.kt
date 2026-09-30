package app.call2remind.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.call2remind.R
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.LampDot
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.components.StepLamps
import app.call2remind.ui.components.TopBar
import app.call2remind.ui.components.staggeredEntrance
import app.call2remind.ui.theme.C2RTheme

/** Steps counted in the lamp progress row. */
const val ONBOARDING_STEPS = 4

/**
 * Shared frame of the onboarding steps: back arrow, lamp progress, headline + body, scrolling
 * content, and a footer pinned to the bottom (actions).
 */
@Composable
fun OnboardingScaffold(
    step: Int,
    headline: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    body: String? = null,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = C2RTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopBar(onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepLamps(step, ONBOARDING_STEPS)
                Text(
                    headline,
                    style = C2RTheme.type.headline,
                    color = c.ink,
                    modifier = Modifier.semantics { heading() },
                )
                if (body != null) {
                    Text(body, style = C2RTheme.type.body, color = c.muted)
                }
            }
            content()
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = footer,
        )
    }
}

/** Test tags for onboarding. */
object OnboardingTags {
    fun row(key: String) = "setup_row_$key"

    fun rowAction(key: String) = "setup_row_action_$key"

    fun rowGranted(key: String) = "setup_row_granted_$key"
}

/**
 * A permission / exemption row: icon tile with a status lamp, title and why-it-matters line, and
 * either "Allow" or a checked "Allowed". The swap springs (the check pops in, the lamp lights).
 */
@Composable
fun SetupRow(
    key: String,
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    showDivider: Boolean = true,
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val line = c.line
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(OnboardingTags.row(key))
            .staggeredEntrance(index)
            .drawBehind {
                if (showDivider) {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            }
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(C2RTheme.shapes.tile)
                    .background(c.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
            }
            LampDot(
                lit = granted,
                size = 11.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = C2RTheme.type.rowTitle, color = c.ink)
            Text(subtitle, style = C2RTheme.type.caption, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        AnimatedContent(
            targetState = granted,
            transitionSpec = {
                (fadeIn(motion.fade()) + scaleIn(motion.playful(), initialScale = 0.8f)) togetherWith fadeOut(motion.fade())
            },
            contentAlignment = Alignment.CenterEnd,
            label = "setupRowTrailing",
        ) { isGranted ->
            if (isGranted) {
                Row(
                    Modifier
                        .defaultMinSize(minWidth = 88.dp)
                        .testTag(OnboardingTags.rowGranted(key)),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(C2RIcons.Check, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.setup_allowed), style = C2RTheme.type.caption, color = c.ink)
                }
            } else {
                PillButton(
                    text = stringResource(R.string.setup_allow),
                    onClick = onAllow,
                    style = PillStyle.Primary,
                    height = 48.dp,
                    textStyle = C2RTheme.type.button,
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    modifier = Modifier.testTag(OnboardingTags.rowAction(key)),
                )
            }
        }
    }
}
