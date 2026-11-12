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
import androidx.compose.ui.platform.LocalConfiguration
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

// Эталонная минимальная ширина экрана в dp (smallest width), на которой вёрстка
// отполирована. У пользователя это «392 DPI» из настроек (на деле — smallest width).
private const val REFERENCE_SMALLEST_WIDTH = 392f

// Нижний предел уменьшения шрифта на маленьких экранах, чтобы текст оставался читаемым.
private const val MIN_SMALL_SCREEN_FONT_SCALE = 0.72f

/**
 * Адаптивный коэффициент размера шрифта относительно эталонных 392 (smallest width).
 * Без верхнего предела: меньше 392 — шрифт уменьшается (компактные экраны),
 * больше 392 — увеличивается. Рост слегка ускоряется на широких экранах
 * (степень 1.2), чтобы на планшетах/ТВ текст был крупнее линейного и
 * хорошо читался с расстояния.
 */
fun adaptiveFontScale(smallestScreenWidthDp: Int): Float {
  val raw = smallestScreenWidthDp.toFloat() / REFERENCE_SMALLEST_WIDTH
  val boosted = Math.pow(raw.toDouble(), 1.2).toFloat()
  return boosted.coerceAtLeast(MIN_SMALL_SCREEN_FONT_SCALE)
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

  // Адаптивное масштабирование шрифтов относительно эталонных 392 (smallest width):
  // на маленьких экранах уменьшаем (нет огромных надписей и переносов),
  // на больших (телевизоры) увеличиваем, чтобы текст читался с расстояния.
  val baseDensity = LocalDensity.current
  val smallestWidth = LocalConfiguration.current.smallestScreenWidthDp
  val effectiveFontScale = baseDensity.fontScale * remember(smallestWidth) {
    adaptiveFontScale(smallestWidth)
  }

  androidx.compose.runtime.CompositionLocalProvider(
    com.example.ui.haptics.LocalHapticEngine provides hapticEngine,
    LocalDensity provides Density(baseDensity.density, effectiveFontScale)
  ) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}
