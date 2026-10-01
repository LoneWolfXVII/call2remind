package app.call2remind.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.call2remind.R
import app.call2remind.core.model.SourceType
import app.call2remind.ui.theme.C2RTheme

/** The stroke icon that stands for a source everywhere (chips, settings rows, timeline). */
val SourceType.icon: ImageVector
    get() = when (this) {
        SourceType.CALENDAR -> C2RIcons.Calendar
        SourceType.GOOGLE_TASKS -> C2RIcons.TaskDone
        SourceType.SAMSUNG_REMINDER -> C2RIcons.Bell
        SourceType.MS_TODO -> C2RIcons.List
        SourceType.HABIT -> C2RIcons.Repeat
        SourceType.BIRTHDAY -> C2RIcons.Cake
    }

/** User-facing source name ("Google Calendar", "Habits"). */
@get:StringRes
val SourceType.labelRes: Int
    get() = when (this) {
        SourceType.CALENDAR -> R.string.source_calendar
        SourceType.GOOGLE_TASKS -> R.string.source_google_tasks
        SourceType.SAMSUNG_REMINDER -> R.string.source_samsung
        SourceType.MS_TODO -> R.string.source_ms_todo
        SourceType.HABIT -> R.string.source_habit
        SourceType.BIRTHDAY -> R.string.source_birthday
    }

/**
 * Icon + source name (+ optional detail), e.g. [calendar icon] "Google Calendar, Work". Inline, no container:
 * the designs treat the source as metadata, not a tag.
 */
@Composable
fun SourceChip(
    source: SourceType,
    modifier: Modifier = Modifier,
    detail: String? = null,
    color: Color = C2RTheme.colors.muted,
    style: TextStyle = C2RTheme.type.caption,
    iconSize: Dp = 16.dp,
) {
    val label = stringResource(source.labelRes)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(source.icon, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
        Text(
            text = if (detail.isNullOrBlank()) label else stringResource(R.string.source_with_detail, label, detail),
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
