package app.call2remind.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.call2remind.R
import app.call2remind.ui.components.BottomNav
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.NavItem
import app.call2remind.ui.components.TopBar
import app.call2remind.ui.theme.C2RTheme

/** Top-level tabs. Phase 2 turns each into its own destination. */
enum class HomeTab(val titleRes: Int) {
    UP_NEXT(R.string.tab_up_next),
    SOURCES(R.string.tab_sources),
    HISTORY(R.string.tab_history),
    SETTINGS(R.string.tab_settings),
}

/**
 * Placeholder for the main shell until Phase 2 builds Up next / Sources / History / Settings: the
 * real top bar and bottom navigation (with the sliding lamp indicator), and an empty body.
 */
@Composable
fun HomePlaceholder(modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    var tab by rememberSaveable { mutableStateOf(HomeTab.UP_NEXT) }
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
                transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.fade()) },
                label = "homeTab",
            ) { current ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopBar(title = stringResource(current.titleRes))
                    if (current == HomeTab.UP_NEXT) {
                        Text(
                            stringResource(R.string.home_placeholder_body),
                            style = C2RTheme.type.body,
                            color = c.muted,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        }
        BottomNav(items = items, selectedKey = tab.name, onSelect = { tab = HomeTab.valueOf(it.key) })
    }
}
