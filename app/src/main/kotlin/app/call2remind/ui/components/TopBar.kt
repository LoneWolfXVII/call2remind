package app.call2remind.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.call2remind.R
import app.call2remind.ui.theme.C2RTheme

/** 64 dp top bar: optional back arrow, optional title, optional trailing actions. */
@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            CircleIconButton(C2RIcons.Back, stringResource(R.string.cd_back), onBack)
        }
        if (title != null) {
            Text(
                title,
                style = C2RTheme.type.screenTitle,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/**
 * Onboarding progress as a row of lamps ("Step 2 of 4"): done and current steps lit, the rest
 * dark. Lamps light with the playful spring as the user advances.
 */
@Composable
fun StepLamps(step: Int, total: Int, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.onboarding_step, step, total)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 1..total) {
                LampDot(lit = i <= step, unlitColor = C2RTheme.colors.line)
            }
        }
        Text(label, style = C2RTheme.type.caption, color = C2RTheme.colors.muted)
    }
}
