package com.itshare.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

val ItShareGreen = Color(0xFF0F806F)
val ItShareGreenDark = Color(0xFF8FE0B3)
val ItShareAccent = Color(0xFF0F806F)

private val DarkColorScheme = darkColorScheme(
    primary = ItShareGreenDark,
    onPrimary = Color(0xFF102018),
    primaryContainer = Color(0xFF214735),
    onPrimaryContainer = Color(0xFFDFF3E8),
    secondary = ItShareGreenDark,
    onSecondary = Color(0xFF102018),
    secondaryContainer = Color(0xFF294438),
    onSecondaryContainer = Color(0xFFDDF4E5),
    tertiary = ItShareGreenDark,
    tertiaryContainer = Color(0xFF3B3322),
    onTertiaryContainer = Color(0xFFFFE7B0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE4EDE7),
    inverseOnSurface = Color(0xFF1B211E),
    inversePrimary = Color(0xFF006B5D),
    background = Color(0xFF141414),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF292929),
    onBackground = Color(0xFFE4EDE7),
    onSurface = Color(0xFFE4EDE7),
    onSurfaceVariant = Color(0xFFB3BDB7),
    outline = Color(0xFF515B55),
    outlineVariant = Color(0xFF383F3B)
)

private val LightColorScheme = lightColorScheme(
    primary = ItShareGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EFEC),
    onPrimaryContainer = Color(0xFF0F5D52),
    secondary = Color(0xFF0F806F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EFEC),
    onSecondaryContainer = Color(0xFF0F5D52),
    tertiary = ItShareAccent,
    tertiaryContainer = Color(0xFFFFE9C2),
    onTertiaryContainer = Color(0xFF80520B),
    background = Color(0xFFF2F8F6),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDF3EF),
    onBackground = Color(0xFF14231F),
    onSurface = Color(0xFF14231F),
    onSurfaceVariant = Color(0xFF5C6E68),
    outline = Color(0xFFCAD8D4),
    outlineVariant = Color(0xFFD9E5E1)
)

private val DefaultTypography = Typography()
private val ItShareTypography = Typography(
    headlineLarge = DefaultTypography.headlineLarge.copy(fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = DefaultTypography.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = DefaultTypography.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = DefaultTypography.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = DefaultTypography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = DefaultTypography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = DefaultTypography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = DefaultTypography.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = DefaultTypography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = DefaultTypography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp)
)

@Composable
fun ItShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = ItShareTypography,
        content = content
    )
}
