package app.call2remind.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme. Components read tokens through [C2RTheme]; a Material 3 scheme is derived from
 * the same tokens so any Material component that slips in (text selection, dialogs) still matches.
 *
 * @param reduceMotion `null` = follow the system animator duration scale.
 */
@Composable
fun Call2RemindTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reduceMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val context = LocalContext.current
    val reduced = reduceMotion ?: remember(context) { C2RMotion.animationsDisabled(context) }
    val motion = remember(reduced) { C2RMotion(reduced) }
    val typography = remember { C2RTypography() }
    val selection = remember(colors) { TextSelectionColors(handleColor = colors.lamp, backgroundColor = colors.lamp.copy(alpha = 0.35f)) }

    CompositionLocalProvider(
        LocalC2RColors provides colors,
        LocalC2RTypography provides typography,
        LocalC2RShapes provides C2RShapes(),
        LocalC2RSpacing provides C2RSpacing(),
        LocalC2RMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = typography.toMaterial(),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides colors.ink,
                LocalTextSelectionColors provides selection,
                content = content,
            )
        }
    }
}

/** Accessors for the current theme tokens. */
object C2RTheme {
    val colors: C2RColors
        @Composable @ReadOnlyComposable
        get() = LocalC2RColors.current

    val type: C2RTypography
        @Composable @ReadOnlyComposable
        get() = LocalC2RTypography.current

    val shapes: C2RShapes
        @Composable @ReadOnlyComposable
        get() = LocalC2RShapes.current

    val spacing: C2RSpacing
        @Composable @ReadOnlyComposable
        get() = LocalC2RSpacing.current

    val motion: C2RMotion
        @Composable @ReadOnlyComposable
        get() = LocalC2RMotion.current
}

internal fun C2RColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = ink,
        onPrimary = surface,
        primaryContainer = tint,
        onPrimaryContainer = ink,
        secondary = panel,
        onSecondary = onPanel,
        secondaryContainer = tint,
        onSecondaryContainer = ink,
        tertiary = lamp,
        onTertiary = onLamp,
        background = ground,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = tint,
        onSurfaceVariant = muted,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = tint,
        inverseSurface = panel,
        inverseOnSurface = onPanel,
        outline = line,
        outlineVariant = line,
        error = missed,
        onError = surface,
        scrim = panel,
    )
}

internal fun C2RTypography.toMaterial(): Typography = Typography(
    displayLarge = display,
    displayMedium = callTitle,
    displaySmall = callTitleSmall,
    headlineLarge = headline,
    headlineMedium = screenTitle,
    headlineSmall = sheetTitle,
    titleLarge = cardTitle,
    titleMedium = rowTitle,
    titleSmall = section,
    bodyLarge = body,
    bodyMedium = caption,
    bodySmall = footnote,
    labelLarge = button,
    labelMedium = caption,
    labelSmall = navLabel,
)
