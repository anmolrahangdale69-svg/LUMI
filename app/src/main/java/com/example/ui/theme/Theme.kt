package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LumiDarkColorScheme = darkColorScheme(
    primary = LumiCyan,
    onPrimary = Color.Black,
    primaryContainer = LumiSurfaceCard,
    onPrimaryContainer = LumiCyanBright,
    secondary = LumiEmerald,
    onSecondary = Color.Black,
    tertiary = LumiIndigo,
    background = LumiBgDark,
    onBackground = LumiTextPrimary,
    surface = LumiSurfaceDark,
    onSurface = LumiTextPrimary,
    surfaceVariant = LumiBorder,
    onSurfaceVariant = LumiTextSecondary,
    error = LumiRose,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Default to the tailored sci-fi dark theme for the robot control cockpit
    MaterialTheme(
        colorScheme = LumiDarkColorScheme,
        typography = Typography,
        content = content
    )
}
