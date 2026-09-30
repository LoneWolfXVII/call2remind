package app.call2remind.ringing

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.call2remind.R
import app.call2remind.scheduling.AndroidAlarmScheduler
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-screen incoming call (shown over the lock screen, turns the screen on). Placeholder UI:
 * Answer / Decline while ringing, Snooze / Done once answered. The design pass restyles it.
 */
@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {
    private val viewModel: IncomingCallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        if (savedInstanceState == null) handleAnswer(intent)
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.finished) { if (state.finished) finish() }
                IncomingCallScreen(
                    state = state,
                    onAnswer = viewModel::answer,
                    onDecline = viewModel::decline,
                    onSnooze = { viewModel.snooze() },
                    onDone = viewModel::done,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OCCURRENCE_ID)?.let(viewModel::bind)
        handleAnswer(intent)
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

@Composable
private fun IncomingCallScreen(
    state: CallUiState,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onSnooze: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))
                Text(
                    text = stringResource(if (state.answered) R.string.call_in_progress else R.string.call_incoming),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = state.title.ifBlank { stringResource(R.string.reminder_fallback_title) },
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                state.notes?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(text = it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (state.answered) {
                    OutlinedButton(onClick = onSnooze) { Text(stringResource(R.string.action_snooze)) }
                    Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
                } else {
                    OutlinedButton(onClick = onDecline) { Text(stringResource(R.string.action_decline)) }
                    Button(onClick = onAnswer) { Text(stringResource(R.string.action_answer)) }
                }
            }
        }
    }
}
