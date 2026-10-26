package com.floweventbus.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colors match the launcher icon: Kotlin purple with a teal "event" accent.
private val Light = lightColorScheme(
    primary = Color(0xFF5B3CC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF1C0062),
    secondary = Color(0xFF00897B),
    secondaryContainer = Color(0xFFB9F3EA),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFFB4690E),
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE6E0F0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF2E0A8F),
    primaryContainer = Color(0xFF4527A0),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFF7CF2E3),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFB9F3EA),
    tertiary = Color(0xFFFFB960),
    tertiaryContainer = Color(0xFF6A3C00),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF14121A),
    surface = Color(0xFF14121A),
    surfaceVariant = Color(0xFF48454F),
)

/** Console colors for the event log, the same in both themes. */
val ConsoleBackground = Color(0xFF1B1726)
val ConsoleText = Color(0xFFE6E1F0)

@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
