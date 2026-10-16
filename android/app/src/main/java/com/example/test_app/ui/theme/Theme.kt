package com.example.test_app.ui.theme

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =====================================================================================================
// THE single theme file. Every color, type style, spacing, radius, elevation, size and motion duration
// in the app comes from here. Change the palette by editing [AppColors] below. See `doc/THEMING.md`.
// Components MUST NOT use Color(0x...) literals or raw dp/sp values for styling.
// =====================================================================================================

/** Color tokens (dark only). Purpose of each token is in `doc/THEMING.md`. */
@Immutable
data class AppColors(
    val background: Color = Color(0xFF0E1116),
    val onBackground: Color = Color(0xFFE8EDF4),
    val surface: Color = Color(0xFF171C24),
    val onSurface: Color = Color(0xFFE8EDF4),
    val surfaceElevated: Color = Color(0xFF202733),
    val primary: Color = Color(0xFF5AA9FF),
    val onPrimary: Color = Color(0xFF06121F),
    val primaryContainer: Color = Color(0xFF16314F),
    val onPrimaryContainer: Color = Color(0xFFD3E6FF),
    val mutedText: Color = Color(0xFF9AA6B8),
    val border: Color = Color(0xFF2C3544),
    val error: Color = Color(0xFFFF7B7B),
    val onError: Color = Color(0xFF2A0707),
    val errorContainer: Color = Color(0xFF3B1517),
    val warning: Color = Color(0xFFFFB84D),
    val onWarning: Color = Color(0xFF2B1A00),
    val success: Color = Color(0xFF4CD08A),
    val onSuccess: Color = Color(0xFF04210F),
    val recordingActive: Color = Color(0xFFFF4D4D),
    val onRecordingActive: Color = Color(0xFFFFFFFF),
    /** Field filled by the AI. */
    val fieldAi: Color = Color(0xFFB39DFF),
    /** Field typed by the user. */
    val fieldUserEdited: Color = Color(0xFF5ED0C0),
    /** Field answered by voice in a clarification round. */
    val fieldUserVoice: Color = Color(0xFF7FD4FF),
    val scrim: Color = Color(0xB3000000),
    /** Categorical palette of the admin graph and charts (12 distinguishable colours on the dark background; indexes are fixed by the graph legend). */
    val graphPalette: List<Color> = listOf(
        Color(0xFFFF8A65), Color(0xFFFFB84D), Color(0xFFE6D45A), Color(0xFF4CD08A), Color(0xFF5ED0C0), Color(0xFF7FD4FF),
        Color(0xFF5AA9FF), Color(0xFFB39DFF), Color(0xFFE48AE0), Color(0xFFFF7B9C), Color(0xFFA7B0BF), Color(0xFF8FBF6A),
    ),
)

/** Spacing tokens. */
@Immutable
data class AppSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
)

/** Corner radius tokens. */
@Immutable
data class AppRadii(
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 20.dp,
    val pill: Dp = 100.dp,
)

/** Elevation tokens. */
@Immutable
data class AppElevation(
    val none: Dp = 0.dp,
    val low: Dp = 2.dp,
    val medium: Dp = 6.dp,
    val high: Dp = 12.dp,
)

/** Size tokens for fixed-size controls. */
@Immutable
data class AppSizes(
    /** Minimum touch target (accessibility). */
    val minTouch: Dp = 48.dp,
    val micButton: Dp = 176.dp,
    /** Space above the big mic on Home so it stays in the same place while idle, recording and processing. */
    val homeMicTopGap: Dp = 72.dp,
    val secondaryButton: Dp = 64.dp,
    val shutterButton: Dp = 72.dp,
    val icon: Dp = 24.dp,
    val iconLarge: Dp = 56.dp,
    val micIcon: Dp = 72.dp,
    val thumbnail: Dp = 88.dp,
    val waveformHeight: Dp = 96.dp,
    val waveformBar: Dp = 6.dp,
    val borderWidth: Dp = 1.dp,
    val borderWidthStrong: Dp = 2.dp,
    val statusDot: Dp = 10.dp,
    val contentMaxWidth: Dp = 640.dp,
)

/** Motion tokens (milliseconds). All animation is skipped when the user enables "reduce motion". */
@Immutable
data class AppMotion(
    val short: Int = 150,
    val medium: Int = 300,
    val long: Int = 600,
    val pulse: Int = 1200,
)

/** Typography tokens. */
@Immutable
data class AppTypography(
    val display: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    val headline: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    /** Monospace style for JSON and ids in the admin dashboard. */
    val code: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    val title: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    val body: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    val bodyLarge: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 28.sp),
    val label: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    val caption: TextStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    val timer: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 40.sp, lineHeight = 48.sp),
)

private val LocalColors = compositionLocalOf { AppColors() }
private val LocalSpacing = compositionLocalOf { AppSpacing() }
private val LocalRadii = compositionLocalOf { AppRadii() }
private val LocalElevation = compositionLocalOf { AppElevation() }
private val LocalSizes = compositionLocalOf { AppSizes() }
private val LocalMotion = compositionLocalOf { AppMotion() }
private val LocalType = compositionLocalOf { AppTypography() }
private val LocalReduceMotion = compositionLocalOf { false }

/** Access point: `AppTheme.colors.primary`, `AppTheme.spacing.md`, ... */
object AppTheme {
    val colors: AppColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val spacing: AppSpacing @Composable @ReadOnlyComposable get() = LocalSpacing.current
    val radii: AppRadii @Composable @ReadOnlyComposable get() = LocalRadii.current
    val elevation: AppElevation @Composable @ReadOnlyComposable get() = LocalElevation.current
    val sizes: AppSizes @Composable @ReadOnlyComposable get() = LocalSizes.current
    val motion: AppMotion @Composable @ReadOnlyComposable get() = LocalMotion.current
    val type: AppTypography @Composable @ReadOnlyComposable get() = LocalType.current

    /** True when the system animator scale is 0 ("remove animations"). */
    val reduceMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/** Root theme composable. Dark mode only. */
@Composable
fun IncidentAssistantTheme(content: @Composable () -> Unit) {
    val c = AppColors()
    val type = AppTypography()
    val r = AppRadii()
    val context = LocalContext.current
    val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    val scheme = darkColorScheme(
        primary = c.primary, onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
        secondary = c.primary, onSecondary = c.onPrimary,
        background = c.background, onBackground = c.onBackground,
        surface = c.surface, onSurface = c.onSurface,
        surfaceVariant = c.surfaceElevated, onSurfaceVariant = c.mutedText,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceElevated,
        surfaceContainerHighest = c.surfaceElevated, surfaceContainerLow = c.surface, surfaceContainerLowest = c.background,
        outline = c.border, outlineVariant = c.border,
        error = c.error, onError = c.onError, errorContainer = c.errorContainer, onErrorContainer = c.error,
        scrim = c.scrim,
    )
    val materialType = Typography(
        displayLarge = type.display, headlineMedium = type.headline, titleLarge = type.title,
        titleMedium = type.title, bodyLarge = type.body, bodyMedium = type.body, bodySmall = type.caption,
        labelLarge = type.label, labelMedium = type.label, labelSmall = type.caption,
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(r.sm), small = RoundedCornerShape(r.sm),
        medium = RoundedCornerShape(r.md), large = RoundedCornerShape(r.lg), extraLarge = RoundedCornerShape(r.lg),
    )
    CompositionLocalProvider(
        LocalColors provides c, LocalType provides type, LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme, typography = materialType, shapes = shapes, content = content)
    }
}
