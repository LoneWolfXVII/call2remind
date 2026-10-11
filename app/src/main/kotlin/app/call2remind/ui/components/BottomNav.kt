package app.call2remind.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.call2remind.ui.theme.C2RTheme
import kotlin.math.abs

/** One destination in [BottomNav]. */
@Immutable
data class NavItem(val key: String, val label: String, val icon: ImageVector)

/**
 * Bottom navigation with the lamp pill indicator. The pill travels between items on the playful
 * spring and stretches while it moves (width grows with the distance still to go), so switching
 * tabs reads as one lamp sliding along the switchboard rather than two lamps blinking.
 */
@Composable
fun BottomNav(
    items: List<NavItem>,
    selectedKey: String,
    onSelect: (NavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val selectedIndex = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val position = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        position.animateTo(selectedIndex.toFloat(), motion.playful(0.001f))
    }
    val lamp = c.lamp
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier
            .fillMaxWidth()
            .background(c.navBg)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .selectableGroup()
                .drawBehind {
                    if (items.isEmpty()) return@drawBehind
                    val itemWidth = size.width / items.size
                    val remaining = abs(position.value - position.targetValue).coerceAtMost(1f)
                    val pillWidth = (64.dp + 28.dp * remaining).toPx()
                    val pillHeight = 32.dp.toPx()
                    val fromStart = itemWidth * (position.value + 0.5f)
                    val centerX = if (rtl) size.width - fromStart else fromStart
                    drawRoundRect(
                        color = lamp,
                        topLeft = Offset(centerX - pillWidth / 2f, 12.dp.toPx()),
                        size = Size(pillWidth, pillHeight),
                        cornerRadius = CornerRadius(pillHeight / 2f),
                    )
                },
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 80.dp)
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(item) },
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        )
                        .padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(width = 64.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                        Icon(item.icon, contentDescription = null, tint = if (selected) c.onLamp else c.ink, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        item.label,
                        style = C2RTheme.type.navLabel.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                        color = c.ink,
                    )
                }
            }
        }
    }
}
