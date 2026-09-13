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
    primary = CinemaPrimary,
    secondary = CinemaSecondary,
    tertiary = CinemaAmber,
    background = CinemaBlack,
    surface = CinemaDark,
    onPrimary = CinemaTextWhite,
    onSecondary = CinemaTextWhite,
    onTertiary = CinemaBlack,
    onBackground = CinemaTextWhite,
    onSurface = CinemaTextWhite
  )

private val LightColorScheme =
  lightColorScheme(
    primary = CinemaPrimary,
    secondary = CinemaSecondary,
    tertiary = CinemaAmber,
    background = CinemaBlack,
    surface = CinemaDark,
    onPrimary = CinemaTextWhite,
    onSecondary = CinemaTextWhite,
    onBackground = CinemaTextWhite,
    onSurface = CinemaTextWhite
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
