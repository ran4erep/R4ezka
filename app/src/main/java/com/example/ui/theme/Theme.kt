package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

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

// Эталонная плотность экрана (density = dpi / 160), на которой вёрстка отполирована:
// 392 DPI → density 2.45.
private const val REFERENCE_DENSITY = 2.45f

// Нижний предел уменьшения шрифта на низких DPI, чтобы текст оставался читаемым.
private const val MIN_LOW_DPI_FONT_SCALE = 0.72f

/**
 * Адаптивный коэффициент размера шрифта относительно эталонных 392 DPI.
 * Полностью линейный: ниже 392 DPI шрифт уменьшается, выше — увеличивается,
 * чтобы на телевизорах (высокие DPI) надписи были крупнее и читались с расстояния.
 * Нижний предел защищает читаемость на очень низких DPI.
 */
@Composable
fun adaptiveFontScale(density: Float = LocalDensity.current.density): Float {
  val raw = density / REFERENCE_DENSITY
  return raw.coerceAtLeast(MIN_LOW_DPI_FONT_SCALE)
}

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window ?: return@SideEffect
      val insetsController = WindowCompat.getInsetsController(window, view)
      insetsController.isAppearanceLightStatusBars = false
      insetsController.isAppearanceLightNavigationBars = false
    }
  }

  val hapticEngine = remember { com.example.ui.haptics.HapticEngine.get() }

  // Адаптивное масштабирование шрифтов относительно эталонных 392 DPI:
  // на низких DPI уменьшаем (нет огромных надписей и переносов),
  // на высоких (телевизоры) увеличиваем, чтобы текст читался с расстояния.
  val baseDensity = LocalDensity.current
  val effectiveFontScale = baseDensity.fontScale * remember(baseDensity.density) {
    adaptiveFontScale(baseDensity.density)
  }

  androidx.compose.runtime.CompositionLocalProvider(
    com.example.ui.haptics.LocalHapticEngine provides hapticEngine,
    LocalDensity provides Density(baseDensity.density, effectiveFontScale)
  ) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}
