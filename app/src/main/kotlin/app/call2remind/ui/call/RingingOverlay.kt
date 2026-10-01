package app.call2remind.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.di.ApplicationScope
import app.call2remind.ringing.IncomingCallActivity
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.ui.theme.C2RTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** The call ringing right now (unanswered), for the in-app banner. */
data class RingingCall(val occurrenceId: String, val title: String, val source: SourceType)

/** Watches Room for a RINGING, unanswered occurrence while the app is open. */
@HiltViewModel
class RingingOverlayViewModel @Inject constructor(
    occurrences: OccurrenceRepository,
    private val engine: SchedulingEngine,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    val ringing: StateFlow<RingingCall?> = occurrences.observeUpcoming()
        .map { list ->
            list.firstOrNull { it.occurrence.state == OccurrenceState.RINGING && it.occurrence.answeredAt == null }
                ?.let { RingingCall(it.occurrence.id, it.reminder?.title.orEmpty(), it.occurrence.sourceType) }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    /** Snooze from the banner = decline (rings again after the default snooze). */
    fun snooze(occurrenceId: String) {
        appScope.launch { engine.handle(occurrenceId, OccurrenceEvent.Decline) }
    }
}

/** Test tag of the in-app call banner. */
const val RINGING_BANNER_TAG = "ringing_banner"

/**
 * The in-app call (HeadsUp design): while a reminder rings and the app is on screen, a
 * [CallBanner] drops in from the top over whatever screen is open (playful spring). Answer opens
 * the call screen; Snooze declines; flicking it up hides it for this ring (it still rings).
 */
@Composable
fun RingingOverlay(viewModel: RingingOverlayViewModel, modifier: Modifier = Modifier) {
    val call by viewModel.ringing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val motion = C2RTheme.motion
    var hiddenFor by rememberSaveable { mutableStateOf<String?>(null) }
    val current = call
    val visible = current != null && hiddenFor != current.occurrenceId
    // Keep the last call composed while the banner leaves.
    val shown = remember { mutableStateOf<RingingCall?>(null) }
    if (current != null) shown.value = current
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(motion.slide()) { -it } + fadeIn(motion.fade()),
        exit = slideOutVertically(motion.slide()) { -it } + fadeOut(motion.fade()),
        modifier = modifier,
    ) {
        val last = shown.value ?: return@AnimatedVisibility
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        val dismissPx = with(LocalDensity.current) { 56.dp.toPx() }
        Box(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .fillMaxWidth()
                .offset { IntOffset(0, drag.value.roundToInt()) }
                .graphicsLayer { alpha = (1f + drag.value / (dismissPx * 2.5f)).coerceIn(0f, 1f) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        // Up freely; down only a little, with resistance.
                        val next = drag.value + if (drag.value + delta > 0f) delta * 0.25f else delta
                        scope.launch { drag.snapTo(next.coerceAtMost(24f)) }
                    },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (drag.value < -dismissPx || velocity < -1_500f) {
                            hiddenFor = last.occurrenceId
                        } else {
                            drag.animateTo(0f, motion.playful(), initialVelocity = velocity)
                        }
                    },
                )
                .testTag(RINGING_BANNER_TAG),
        ) {
            CallBanner(
                title = last.title,
                source = last.source,
                detail = null,
                onAnswer = { context.startActivity(IncomingCallActivity.intent(context, last.occurrenceId, answer = true)) },
                onSnooze = { viewModel.snooze(last.occurrenceId) },
            )
        }
    }
}
