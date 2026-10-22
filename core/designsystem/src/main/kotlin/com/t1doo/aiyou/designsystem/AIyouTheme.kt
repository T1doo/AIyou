package com.t1doo.aiyou.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val palette = lightColorScheme(
    primary = AIyouColors.primary,
    onPrimary = AIyouColors.onPrimary,
    primaryContainer = AIyouColors.primaryContainer,
    onPrimaryContainer = AIyouColors.primary,
    secondary = AIyouColors.primary,
    onSecondary = AIyouColors.onPrimary,
    secondaryContainer = AIyouColors.surfaceMuted,
    onSecondaryContainer = AIyouColors.textPrimary,
    tertiary = AIyouColors.success,
    onTertiary = AIyouColors.onPrimary,
    tertiaryContainer = AIyouColors.successContainer,
    onTertiaryContainer = AIyouColors.success,
    background = AIyouColors.background,
    onBackground = AIyouColors.textPrimary,
    surface = AIyouColors.surface,
    onSurface = AIyouColors.textPrimary,
    surfaceVariant = AIyouColors.surfaceMuted,
    onSurfaceVariant = AIyouColors.textSecondary,
    surfaceTint = AIyouColors.primary,
    surfaceDim = AIyouColors.surfaceMuted,
    surfaceBright = AIyouColors.surface,
    surfaceContainerLowest = AIyouColors.surface,
    surfaceContainerLow = AIyouColors.background,
    surfaceContainer = AIyouColors.surfaceMuted,
    surfaceContainerHigh = AIyouColors.surfaceMuted,
    surfaceContainerHighest = AIyouColors.primaryContainer,
    inverseSurface = AIyouColors.textPrimary,
    inverseOnSurface = AIyouColors.surface,
    inversePrimary = AIyouColors.primaryContainer,
    outline = AIyouColors.outlineFunctional,
    outlineVariant = AIyouColors.outlineDecorative,
    error = AIyouColors.error,
    onError = AIyouColors.onPrimary,
    errorContainer = AIyouColors.errorContainer,
    onErrorContainer = AIyouColors.error,
    scrim = AIyouColors.textPrimary,
)

private val title = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = AIyouTypeScale.titleSize,
    lineHeight = AIyouTypeScale.titleLineHeight,
    fontWeight = FontWeight.SemiBold,
)
private val section = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = AIyouTypeScale.sectionSize,
    lineHeight = AIyouTypeScale.sectionLineHeight,
    fontWeight = FontWeight.Medium,
)
private val body = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = AIyouTypeScale.bodySize,
    lineHeight = AIyouTypeScale.bodyLineHeight,
)
private val supporting = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = AIyouTypeScale.supportingSize,
    lineHeight = AIyouTypeScale.supportingLineHeight,
)

/** Fixed light notebook palette; system fonts and sp retain user font scaling. */
@Composable
fun AIyouTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = palette,
        typography = Typography(
            displayLarge = title, displayMedium = title, displaySmall = title,
            headlineLarge = title, headlineMedium = title, headlineSmall = section,
            titleLarge = section, titleMedium = body.copy(fontWeight = FontWeight.SemiBold),
            titleSmall = supporting.copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = body, bodyMedium = body, bodySmall = supporting,
            labelLarge = supporting.copy(fontWeight = FontWeight.SemiBold),
            labelMedium = supporting.copy(fontWeight = FontWeight.Medium), labelSmall = supporting,
        ),
        shapes = Shapes(
            small = RoundedCornerShape(AIyouDimensions.spaceSm),
            medium = RoundedCornerShape(AIyouDimensions.cardRadius),
            large = RoundedCornerShape(AIyouDimensions.composerRadius),
        ),
        content = content,
    )
}
