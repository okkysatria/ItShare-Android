package com.itshare.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ItShareNavy = Color(0xFF0F5136)
val ItShareNavyLight = Color(0xFF176B49)
val ItShareGreen = Color(0xFF176B49)
val ItShareGreenDark = Color(0xFF8FE0B3)
val ItShareTeal = Color(0xFF2C9968)
val ItShareAccent = Color(0xFF2C9968)
val ItShareAmber = Color(0xFF9A6514)

private val DarkColorScheme = darkColorScheme(
    primary = ItShareGreenDark,
    onPrimary = Color(0xFF102018),
    primaryContainer = Color(0xFF214735),
    onPrimaryContainer = Color(0xFFDFF3E8),
    secondary = ItShareTeal,
    onSecondary = Color(0xFF102018),
    secondaryContainer = Color(0xFF294438),
    onSecondaryContainer = Color(0xFFDDF4E5),
    tertiary = ItShareAccent,
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
    primaryContainer = Color(0xFFDFF3E8),
    onPrimaryContainer = Color(0xFF0F5136),
    secondary = Color(0xFF2C9968),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDF8F1),
    onSecondaryContainer = Color(0xFF14563A),
    tertiary = ItShareAccent,
    background = Color(0xFFF5F7F3),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDF3EF),
    onBackground = Color(0xFF17211C),
    onSurface = Color(0xFF17211C),
    onSurfaceVariant = Color(0xFF68756E),
    outline = Color(0xFFCAD5CD),
    outlineVariant = Color(0xFFDFE5DF)
)

@Composable
fun ItShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}
