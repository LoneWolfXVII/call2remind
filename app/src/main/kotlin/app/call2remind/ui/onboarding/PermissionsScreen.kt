package app.call2remind.ui.onboarding

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.R
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.PillButton
import app.call2remind.ui.components.PillStyle
import app.call2remind.ui.system.SetupItem
import app.call2remind.ui.system.SystemIntents
import app.call2remind.ui.theme.C2RTheme

/** Row copy and icon per [SetupItem]. */
private data class SetupCopy(val icon: ImageVector, val title: Int, val subtitle: Int)

private fun SetupItem.setupCopy(): SetupCopy = when (this) {
    SetupItem.NOTIFICATIONS -> SetupCopy(C2RIcons.Bell, R.string.setup_notifications, R.string.setup_notifications_why)
    SetupItem.FULL_SCREEN -> SetupCopy(C2RIcons.FullScreen, R.string.setup_full_screen, R.string.setup_full_screen_why)
    SetupItem.EXACT_ALARMS -> SetupCopy(C2RIcons.Clock, R.string.setup_exact_alarms, R.string.setup_exact_alarms_why)
    SetupItem.CALENDAR -> SetupCopy(C2RIcons.Calendar, R.string.setup_calendar, R.string.setup_calendar_why)
    SetupItem.CONTACTS -> SetupCopy(C2RIcons.Person, R.string.setup_contacts, R.string.setup_contacts_why)
}

/** Stateful Permissions step: real runtime requests and settings deep links; re-checks on resume. */
@Composable
fun PermissionsStep(
    viewModel: OnboardingViewModel,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pending by rememberSaveable { mutableStateOf<SetupItem?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val permission = viewModel.permissionFor(item)
        val canAskAgain = permission != null && activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        viewModel.onPermissionResult(item, granted, canAskAgain)
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    PermissionsScreen(
        state = state,
        onAllow = { item ->
            when (state.actionFor(item)) {
                RowAction.REQUEST -> {
                    val permission = viewModel.permissionFor(item)
                    if (permission != null) {
                        pending = item
                        launcher.launch(permission)
                    } else {
                        SystemIntents.launchFirst(context, settingsFor(context, item))
                    }
                }
                RowAction.OPEN_SETTINGS -> SystemIntents.launchFirst(context, settingsFor(context, item))
                RowAction.NONE -> Unit
            }
        },
        onContinue = {
            viewModel.onPermissionsDone()
            onContinue()
        },
        onBack = onBack,
    )
}

private fun settingsFor(context: Context, item: SetupItem) = when (item) {
    SetupItem.NOTIFICATIONS -> SystemIntents.notifications(context)
    SetupItem.FULL_SCREEN -> SystemIntents.fullScreenIntent(context)
    SetupItem.EXACT_ALARMS -> SystemIntents.exactAlarms(context)
    SetupItem.CALENDAR, SetupItem.CONTACTS -> listOf(SystemIntents.appDetails(context))
}

/** Stateless Permissions step. */
@Composable
fun PermissionsScreen(
    state: OnboardingUiState,
    onAllow: (SetupItem) -> Unit,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val items = SetupItem.entries
    OnboardingScaffold(
        step = 2,
        headline = stringResource(R.string.permissions_headline),
        body = stringResource(R.string.permissions_body),
        onBack = onBack,
        modifier = modifier,
        footer = {
            Text(
                stringResource(R.string.permissions_summary, state.setup.grantedCount, items.size),
                style = C2RTheme.type.caption,
                color = c.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            PillButton(
                text = stringResource(R.string.action_continue),
                onClick = onContinue,
                style = PillStyle.Primary,
                height = 60.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
            items.forEachIndexed { index, item ->
                val copy = item.setupCopy()
                val blocked = state.actionFor(item) == RowAction.OPEN_SETTINGS && item in state.blocked
                SetupRow(
                    key = item.name,
                    icon = copy.icon,
                    title = stringResource(copy.title),
                    subtitle = if (blocked) stringResource(R.string.setup_blocked) else stringResource(copy.subtitle),
                    granted = state.setup.isGranted(item),
                    onAllow = { onAllow(item) },
                    index = index,
                    showDivider = index < items.lastIndex,
                )
            }
        }
    }
}
