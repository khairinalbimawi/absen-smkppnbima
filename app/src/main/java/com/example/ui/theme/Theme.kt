package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = SleekPrimary,
    onPrimary = SleekOnPrimary,
    secondary = SleekSecondary,
    onSecondary = SleekOnSecondary,
    background = SleekBackground,
    onBackground = SleekOnBackground,
    surface = SleekSurface,
    onSurface = SleekOnSurface,
    surfaceVariant = SleekSurfaceVariant,
    onSurfaceVariant = SleekOnSurface,
    outline = SleekOutline,
    outlineVariant = SleekDivider
  )

private val LightColorScheme = DarkColorScheme // Force Sleek interface beautiful dark theme for biometric experience

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark theme for the Sleek Interface biometric experience
  dynamicColor: Boolean = false, // Disable dynamic colors to keep layout design uniform
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme


  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
