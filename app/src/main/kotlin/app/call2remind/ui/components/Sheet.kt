package app.call2remind.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.call2remind.R
import app.call2remind.ui.theme.C2RTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Test tags of the shared sheet. */
object SheetTags {
    const val SHEET = "c2r_sheet"
    const val SCRIM = "c2r_sheet_scrim"
}

/** What a screen asked the [SheetHostLayer] to show. */
internal class SheetSpec(
    val owner: Any,
    val label: String,
    val onDismiss: () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
)

/**
 * App-wide sheet slot. Screens declare a [C2RSheet] where it belongs in their code; the sheet is
 * drawn by the [SheetHostLayer] at the root, above the bottom navigation and every destination,
 * so a sheet opened from a tab covers the tab bar like a real modal.
 */
@Stable
class SheetController {
    internal var current by mutableStateOf<SheetSpec?>(null)
        private set

    internal fun show(owner: Any, label: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        current = SheetSpec(owner, label, onDismiss, content)
    }

    internal fun hide(owner: Any) {
        if (current?.owner === owner) current = null
    }
}

val LocalSheetController = staticCompositionLocalOf<SheetController?> { null }

/**
 * Hosts [C2RSheet]s for [content]: provides the [SheetController] and draws the active sheet (and
 * its scrim) on top. Put it once around the NavHost; tests wrap a single screen in it.
 */
@Composable
fun SheetHostLayer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val controller = remember { SheetController() }
    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSheetController provides controller) {
            content()
        }
        val active = controller.current
        // The last sheet stays composed while it animates out.
        var last by remember { mutableStateOf<SheetSpec?>(null) }
        SideEffect { if (active != null) last = active }
        val showing = active ?: last
        if (showing != null) {
            SheetFrame(
                open = active != null,
                label = showing.label,
                onDismiss = showing.onDismiss,
                onHidden = { if (controller.current == null) last = null },
                content = showing.content,
            )
        }
    }
}

/**
 * A bottom sheet: slides up on the default spring (a touch of overshoot) over a panel-tinted
 * scrim, follows a downward drag on its handle and dismisses past 30 % or on a fling; tapping the
 * scrim or pressing back dismisses. Content taller than 85 % of the screen scrolls.
 *
 * Declared inline by the screen that owns it; drawn by the nearest [SheetHostLayer] (or in
 * place, filling the parent, when there is none).
 */
@Composable
fun C2RSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val controller = LocalSheetController.current
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    if (controller == null) {
        var visible by remember { mutableStateOf(open) }
        if (open) visible = true
        if (visible) {
            SheetFrame(open = open, label = label, onDismiss = { latestDismiss() }, onHidden = { visible = false }) { latestContent() }
        }
        return
    }
    val owner = remember { Any() }
    val stableContent: @Composable ColumnScope.() -> Unit = remember { { latestContent() } }
    val stableDismiss: () -> Unit = remember { { latestDismiss() } }
    LaunchedEffect(open, label) {
        if (open) controller.show(owner, label, stableDismiss, stableContent) else controller.hide(owner)
    }
    DisposableEffect(controller) {
        onDispose { controller.hide(owner) }
    }
}

@Composable
private fun SheetFrame(
    open: Boolean,
    label: String,
    onDismiss: () -> Unit,
    onHidden: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = C2RTheme.motion
    val c = C2RTheme.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val hidden = remember { Animatable(1f) }
    var height by remember { mutableIntStateOf(0) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentHidden by rememberUpdatedState(onHidden)
    LaunchedEffect(open) {
        hidden.animateTo(if (open) 0f else 1f, motion.default(0.0005f))
        if (!open) currentHidden()
    }
    val scrimAlpha by remember { derivedStateOf { (1f - hidden.value).coerceIn(0f, 1f) * SCRIM_ALPHA } }
    BackHandler(enabled = open) { currentDismiss() }
    val flingPx = with(density) { 1_200.dp.toPx() }
    val scrim = c.panel

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxSheet = maxHeight * MAX_HEIGHT_FRACTION
        Box(
            Modifier
                .matchParentSize()
                .drawBehind { drawRect(scrim.copy(alpha = scrimAlpha)) }
                .testTag(SheetTags.SCRIM)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    enabled = open,
                    onClickLabel = stringResource(R.string.sheet_close),
                ) { currentDismiss() },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .heightIn(max = maxSheet)
                .onSizeChanged { height = it.height }
                .offset { IntOffset(0, (hidden.value.coerceAtLeast(-0.03f) * height).roundToInt()) }
                // Shape drawn, not clipped: a clip layer with top-only corners would need path-based
                // hit testing for every touch on the sheet.
                .background(c.surface, C2RTheme.shapes.sheet)
                .semantics { paneTitle = label }
                .testTag(SheetTags.SHEET)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            // Drag handle: the grab area is the whole top strip, so the scrolling body keeps its own drags.
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onVerticalDrag = { change, delta ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (height > 0) scope.launch { hidden.snapTo((hidden.value + delta / height).coerceIn(-0.03f, 1f)) }
                            },
                            onDragEnd = {
                                val velocity = tracker.calculateVelocity().y
                                if (hidden.value > DISMISS_FRACTION || velocity > flingPx) {
                                    currentDismiss()
                                } else {
                                    scope.launch {
                                        hidden.animateTo(0f, motion.default(0.0005f), initialVelocity = if (height > 0) velocity / height else 0f)
                                    }
                                }
                            },
                            onDragCancel = { scope.launch { hidden.animateTo(0f, motion.default(0.0005f)) } },
                        )
                    }
                    .padding(top = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(c.line, C2RTheme.shapes.pill),
                )
            }
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .graphicsLayer { alpha = (1f - hidden.value * 0.6f).coerceIn(0f, 1f) }
                    .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                content = content,
            )
        }
    }
}

private const val DISMISS_FRACTION = 0.3f
private const val SCRIM_ALPHA = 0.42f
private const val MAX_HEIGHT_FRACTION = 0.88f
