package app.call2remind.ringing

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.call2remind.scheduling.AndroidAlarmScheduler
import app.call2remind.settings.ThemeMode
import app.call2remind.ui.call.CallActions
import app.call2remind.ui.call.CallPresentationViewModel
import app.call2remind.ui.call.CallScreen
import app.call2remind.ui.navigation.AppViewModel
import app.call2remind.ui.system.SystemIntents
import app.call2remind.ui.theme.Call2RemindTheme
import app.call2remind.ui.theme.LightColors
import app.call2remind.ui.theme.preloadFonts
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Full-screen incoming call (shown over the lock screen, turns the screen on): the plug-into-socket
 * answer, the answered call with the voice transcript, and the snooze sheet ([CallScreen]).
 *
 * [IncomingCallViewModel] owns the call's state transitions; [CallPresentationViewModel] adds what
 * only the screen needs (snooze allowance, transcript, voice controls).
 */
@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {
    private val viewModel: IncomingCallViewModel by viewModels()
    private val presentationViewModel: CallPresentationViewModel by viewModels()
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val panel = LightColors.panel.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Without an occurrence there is nothing to show (and nothing would ever finish the screen).
        if (intent?.getStringExtra(EXTRA_OCCURRENCE_ID) == null) {
            finish()
            return
        }
        window.setBackgroundDrawable(ColorDrawable(panel))
        showOverLockScreen()
        if (savedInstanceState == null) handleAnswer(intent)
        lifecycleScope.launch { preloadFonts(applicationContext) }
        setContent {
            // The in-app theme setting (Settings → Theme) applies here too, not just the system's.
            val themeMode by appViewModel.theme.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            Call2RemindTheme(darkTheme = dark) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val presentation by presentationViewModel.presentation.collectAsStateWithLifecycle()
                val speech by presentationViewModel.speech.collectAsStateWithLifecycle()
                LaunchedEffect(state.finished) { if (state.finished) finish() }
                val sourceIntents = remember(state.sourceType, presentation.plannedAt, state.fireAt) {
                    state.sourceType?.let { SystemIntents.openSource(it, presentation.plannedAt ?: state.fireAt) }.orEmpty()
                }
                val actions = remember(sourceIntents) {
                    CallActions(
                        onAnswer = viewModel::answer,
                        onDecline = viewModel::decline,
                        onSnooze = { viewModel.snooze(it) },
                        onDone = viewModel::done,
                        onOpenSource = if (sourceIntents.isEmpty()) null else ({ openSource(sourceIntents) }),
                        onReadAgain = presentationViewModel::readAgain,
                        onStopVoice = presentationViewModel::stopVoice,
                        onBack = ::sendToBackground,
                    )
                }
                CallScreen(state = state, presentation = presentation, speech = speech, actions = actions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OCCURRENCE_ID)?.let { id ->
            viewModel.bind(id)
            presentationViewModel.bind(id)
        }
        handleAnswer(intent)
    }

    /** Back while the call is up: keep ringing / talking in the background; only a finished call closes. */
    private fun sendToBackground() {
        if (viewModel.state.value.finished) finish() else moveTaskToBack(true)
    }

    /**
     * The source app can't open over the lock screen: ask to unlock first and launch once the
     * keyguard is gone (nothing happens if the user cancels). Unlocked, launch straight away.
     */
    private fun openSource(intents: List<Intent>) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            SystemIntents.launchFirst(this, intents)
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    SystemIntents.launchFirst(this@IncomingCallActivity, intents)
                }
            },
        )
    }

    private fun handleAnswer(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) viewModel.answer()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val ACTION_SHOW = "app.call2remind.action.SHOW_CALL"
        const val ACTION_ANSWER = "app.call2remind.action.ANSWER_CALL"

        fun intent(context: Context, occurrenceId: String, answer: Boolean): Intent =
            Intent(context, IncomingCallActivity::class.java)
                .setAction(if (answer) ACTION_ANSWER else ACTION_SHOW)
                .setData(AndroidAlarmScheduler.occurrenceUri(occurrenceId))
                .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
