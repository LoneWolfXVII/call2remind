package app.call2remind

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.call2remind.sync.CalendarChangeObserver
import app.call2remind.sync.SyncScheduler
import app.call2remind.ui.navigation.AppViewModel
import app.call2remind.ui.navigation.C2RNavHost
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Call2RemindTheme
import app.call2remind.ui.theme.DarkColors
import app.call2remind.ui.theme.LightColors
import app.call2remind.ui.theme.preloadFonts
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var calendarObserver: CalendarChangeObserver

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Paint the ground before the first frame so there is no white flash (in either theme).
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable((if (night) DarkColors else LightColors).ground.toArgb()))
        lifecycleScope.launch { preloadFonts(applicationContext) }
        setContent {
            Call2RemindTheme {
                val start by appViewModel.start.collectAsStateWithLifecycle()
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(C2RTheme.colors.ground),
                ) {
                    start?.let { C2RNavHost(start = it) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // App open: expedited one-shot sync of every source; permissions may have been granted meanwhile.
        calendarObserver.ensureRegistered()
        syncScheduler.requestSync()
    }
}
