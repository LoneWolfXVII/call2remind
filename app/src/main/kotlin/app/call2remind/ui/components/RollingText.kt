package app.call2remind.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import app.call2remind.ui.theme.C2RTheme

/**
 * Text whose characters roll individually when they change, like a split-flap counter: a new
 * digit drops in from above while the old one falls away (precise spring). Meant for tabular
 * mono numbers (countdowns, timers), where every glyph has the same width.
 */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    contentDescription: String = text,
) {
    val motion = C2RTheme.motion
    Row(modifier.clearAndSetSemantics { this.contentDescription = contentDescription }) {
        text.forEachIndexed { index, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    if (motion.reduced) {
                        fadeIn(motion.fade()) togetherWith fadeOut(motion.fade())
                    } else {
                        (slideInVertically(motion.slide()) { -it / 2 } + fadeIn(motion.fade())) togetherWith
                            (slideOutVertically(motion.slide()) { it / 2 } + fadeOut(motion.fade()))
                    }
                },
                label = "rolling$index",
            ) { c ->
                Text(c.toString(), style = style, color = color)
            }
        }
    }
}
