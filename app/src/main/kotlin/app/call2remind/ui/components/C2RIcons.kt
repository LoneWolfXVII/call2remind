package app.call2remind.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The hand-drawn stroke icon set from the design files: 24×24 viewport, round caps and joins,
 * 1.8 stroke unless noted. Paths are the mockups' SVG paths verbatim; `<circle>` and `<rect>`
 * elements are expressed as equivalent path data ([circle], [roundRect]).
 *
 * Icons are drawn in black and tinted by `Icon(tint = …)`.
 */
object C2RIcons {
    val Phone: ImageVector by lazy {
        icon("Phone", 2f, "M5.5 3.5h3.2l1.8 4.6-2.3 1.4a11 11 0 0 0 6.3 6.3l1.4-2.3 4.6 1.8v3.2a2 2 0 0 1-2.2 2A16.5 16.5 0 0 1 3.5 5.7a2 2 0 0 1 2-2.2z")
    }
    val Calendar: ImageVector by lazy {
        icon("Calendar", STROKE, roundRect(3.5f, 5f, 17f, 15f, 2.5f), "M3.5 10h17M8 3v4M16 3v4")
    }
    val Check: ImageVector by lazy { icon("Check", 2.2f, "M5 12.5l4.5 4.5L19 7") }
    val Snooze: ImageVector by lazy {
        icon("Snooze", STROKE, circle(12f, 13f, 7.5f), "M12 9.5V13l2.5 1.6M5 3.8 2.6 6.2M19 3.8l2.4 2.4")
    }
    val OpenExternal: ImageVector by lazy {
        icon("OpenExternal", STROKE, "M14 4h6v6M20 4l-8.5 8.5", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
    }
    val Replay: ImageVector by lazy { icon("Replay", STROKE, "M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4", "M10.5 9.5v5l4-2.5z") }
    val Speaker: ImageVector by lazy {
        icon("Speaker", STROKE, "M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11")
    }
    val Back: ImageVector by lazy { icon("Back", STROKE, "M19 12H5M11 6l-6 6 6 6") }
    val Plus: ImageVector by lazy { icon("Plus", STROKE, "M12 5v14M5 12h14") }
    val Minus: ImageVector by lazy { icon("Minus", STROKE, "M5 12h14") }
    val Close: ImageVector by lazy { icon("Close", STROKE, "M6 6l12 12M18 6 6 18") }
    val More: ImageVector by lazy { icon("More", STROKE, "M12 5.5h.01M12 12h.01M12 18.5h.01") }
    val Sync: ImageVector by lazy { icon("Sync", STROKE, "M20 11a8 8 0 0 0-14.5-4.5M4 4v3h3", "M4 13a8 8 0 0 0 14.5 4.5M20 20v-3h-3") }
    val Plug: ImageVector by lazy { icon("Plug", STROKE, "M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4") }
    val History: ImageVector by lazy { icon("History", STROKE, "M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4", "M12 8v4l3 2") }
    val Sliders: ImageVector by lazy {
        icon("Sliders", STROKE, "M4 7h9M17 7h3M4 17h3M11 17h9", circle(15f, 7f, 2f), circle(9f, 17f, 2f))
    }
    val Repeat: ImageVector by lazy {
        icon("Repeat", STROKE, "M4 11V9.5A3.5 3.5 0 0 1 7.5 6H19l-3-3", "M20 13v1.5a3.5 3.5 0 0 1-3.5 3.5H5l3 3")
    }
    val TaskDone: ImageVector by lazy { icon("TaskDone", STROKE, circle(12f, 12f, 8.5f), "M8.5 12.2l2.4 2.3 4.6-4.8") }
    val Bell: ImageVector by lazy { icon("Bell", STROKE, "M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20.5a2 2 0 0 0 4 0") }
    val Cake: ImageVector by lazy {
        icon(
            "Cake",
            STROKE,
            "M4 20h16M5 20v-7h14v7",
            "M5 16.5c1.7 1.3 3.3 1.3 5 0s3.3-1.3 5 0 3 1.3 4 0",
            "M12 13v-3",
            "M12 5c.9 1 .9 2 0 2.8-.9-.8-.9-1.8 0-2.8z",
        )
    }
    val Trend: ImageVector by lazy { icon("Trend", 2f, "M3 8l5 5 4-4 5 5", "M13 14h4v-4") }
    val Music: ImageVector by lazy {
        icon("Music", STROKE, "M9 18V6l10-2v12", circle(6.5f, 18f, 2.5f), circle(16.5f, 16f, 2.5f))
    }
    val ChevronRight: ImageVector by lazy { icon("ChevronRight", STROKE, "M9.5 6l6 6-6 6") }
    val FullScreen: ImageVector by lazy { icon("FullScreen", STROKE, "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5") }
    val Clock: ImageVector by lazy { icon("Clock", STROKE, circle(12f, 12f, 8.5f), "M12 7.5V12l3 2") }
    val Person: ImageVector by lazy { icon("Person", STROKE, circle(12f, 8.5f, 3.5f), "M5 20c.8-3.5 3.6-5.5 7-5.5s6.2 2 7 5.5") }
    val Pause: ImageVector by lazy { icon("Pause", 2f, "M8 5.5v13M16 5.5v13") }
    val Play: ImageVector by lazy { icon("Play", 2f, "M8 5.5v13l10.5-6.5z") }
    val Lock: ImageVector by lazy { icon("Lock", STROKE, roundRect(5f, 10.5f, 14f, 10f, 2.5f), "M8 10.5V8a4 4 0 0 1 8 0v2.5") }
    val Moon: ImageVector by lazy { icon("Moon", STROKE, "M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5z") }
    val List: ImageVector by lazy { icon("List", STROKE, "M9 7h11M9 12h11M9 17h11", "M4 7h.01M4 12h.01M4 17h.01") }
    val Battery: ImageVector by lazy {
        icon("Battery", STROKE, roundRect(3.5f, 7.5f, 15f, 9f, 2f), "M21 10.5v3M7 10.5v3")
    }

    /** Every icon, for previews and the parsing test. */
    val all: List<ImageVector>
        get() = listOf(
            Phone, Calendar, Check, Snooze, OpenExternal, Replay, Speaker, Back, Plus, Minus, Close, More, Sync,
            Plug, History, Sliders, Repeat, TaskDone, Bell, Cake, Trend, Music, ChevronRight, FullScreen, Clock,
            Person, Pause, Play, Lock, Moon, List, Battery,
        )

    private const val STROKE = 1.8f
    private const val VIEWPORT = 24f

    private fun icon(name: String, strokeWidth: Float, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = "C2R.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        )
        for (d in paths) {
            builder.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    /** SVG `<circle>` as path data (two half arcs). */
    internal fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    /** SVG `<rect rx>` as path data. */
    internal fun roundRect(x: Float, y: Float, w: Float, h: Float, rx: Float): String =
        "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}" +
            "h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"
}
