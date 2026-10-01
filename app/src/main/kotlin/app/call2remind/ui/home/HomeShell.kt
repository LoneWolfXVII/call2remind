package app.call2remind.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.R
import app.call2remind.ringing.IncomingCallActivity
import app.call2remind.ui.components.BottomNav
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.NavItem
import app.call2remind.ui.format.rememberMinuteTicker
import app.call2remind.ui.history.HistoryRoute
import app.call2remind.ui.settings.SettingsNav
import app.call2remind.ui.settings.SettingsRoute
import app.call2remind.ui.sources.SourcesRoute
import app.call2remind.ui.theme.C2RTheme

/** Top-level tabs. */
enum class HomeTab {
    UP_NEXT,
    SOURCES,
    HISTORY,
    SETTINGS,
}

/** Where the tabs can send the user (the NavHost implements these). */
data class HomeNav(
    val openDetail: (occurrenceId: String, reminderId: String, sharedKey: String) -> Unit,
    val newHabit: () -> Unit,
    val editHabit: (habitId: String) -> Unit,
    val settings: SettingsNav,
)

/**
 * The main shell: the four tabs over the bottom navigation (with its sliding lamp). Each tab keeps
 * its state (scroll position, open sections) while another is shown; switching cross-fades with
 * a slight settle so it reads as the same switchboard, not a new screen.
 */
@Composable
fun HomeShell(nav: HomeNav, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    var tab by rememberSaveable { mutableStateOf(HomeTab.UP_NEXT) }
    val holder = rememberSaveableStateHolder()
    val items = listOf(
        NavItem(HomeTab.UP_NEXT.name, stringResource(R.string.tab_up_next), C2RIcons.Phone),
        NavItem(HomeTab.SOURCES.name, stringResource(R.string.tab_sources), C2RIcons.Plug),
        NavItem(HomeTab.HISTORY.name, stringResource(R.string.tab_history), C2RIcons.History),
        NavItem(HomeTab.SETTINGS.name, stringResource(R.string.tab_settings), C2RIcons.Sliders),
    )
    Column(
        modifier
            .fillMaxSize()
            .background(c.ground),
    ) {
        Box(
            Modifier
                .weight(1f)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(motion.fade()) + scaleIn(motion.precise(), initialScale = 0.985f)) togetherWith fadeOut(motion.fade())
                },
                label = "homeTab",
            ) { current ->
                holder.SaveableStateProvider(current.name) {
                    when (current) {
                        HomeTab.UP_NEXT -> UpNextRoute(
                            onOpenDetail = nav.openDetail,
                            onNewHabit = nav.newHabit,
                            onCheckSources = { tab = HomeTab.SOURCES },
                        )
                        HomeTab.SOURCES -> SourcesRoute(onNewHabit = nav.newHabit, onEditHabit = nav.editHabit)
                        HomeTab.HISTORY -> HistoryRoute()
                        HomeTab.SETTINGS -> SettingsRoute(nav = nav.settings)
                    }
                }
            }
        }
        BottomNav(items = items, selectedKey = tab.name, onSelect = { tab = HomeTab.valueOf(it.key) })
    }
}

/** Up next wired to its ViewModel, the minute ticker and the call screen. */
@Composable
fun UpNextRoute(
    onOpenDetail: (occurrenceId: String, reminderId: String, sharedKey: String) -> Unit,
    onNewHabit: () -> Unit,
    onCheckSources: () -> Unit,
    viewModel: UpNextViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by rememberMinuteTicker()
    val context = LocalContext.current
    // Each minute: re-group (Today / Tomorrow follow midnight) and refresh countdowns.
    LaunchedEffect(now) { viewModel.tick() }
    UpNextScreen(
        state = state,
        now = now,
        actions = UpNextActions(
            onOpen = { row, key -> onOpenDetail(row.occurrenceId, row.reminderId, key) },
            onOpenCall = { id -> context.startActivity(IncomingCallActivity.intent(context, id, answer = false)) },
            onSwipe = viewModel::onSwipe,
            onUndo = viewModel::undo,
            onUndoTimeout = viewModel::commitPending,
            onSync = viewModel::syncNow,
            onNewHabit = onNewHabit,
            onCheckSources = onCheckSources,
        ),
    )
}
