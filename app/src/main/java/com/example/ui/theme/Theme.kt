package com.example.ui.theme

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.example.data.RezkaService
import com.example.ui.tv.TvDetector

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

/**
 * Безопасный коэффициент шрифта для ТВ-интерфейса (10-ft UI).
 *
 * Вместо старой бесконечной кривой (w/392)^1.2 здесь используется класс ширины окна
 * (те же брейкпоинты, что у официального WindowSizeClass: <600 / 600–840 / ≥840 dp),
 * а рост жёстко ограничен сверху (≤1.22). Такое масштабирование:
 *  - не даёт тексту стать слишком мелким на ТВ (10-ft UI);
 *  - не раздувает шрифт до поломки вёрстки (потолок вместо ×2.9+);
 *  - никогда не трогает системный fontScale пользователя (он применяется отдельно).
 */
fun tvInterfaceTextScale(widthDp: Int): Float = when {
  widthDp < 600 -> 1.10f
  widthDp < 840 -> 1.18f
  else -> 1.22f
}

private fun tvScaleForWindowClass(widthClass: WindowWidthSizeClass): Float = when (widthClass) {
  WindowWidthSizeClass.Compact -> 1.10f
  WindowWidthSizeClass.Medium -> 1.18f
  WindowWidthSizeClass.Expanded -> 1.22f
  else -> 1.22f
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
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

  // --- Масштабирование шрифтов ------------------------------------------------
  // Системный fontScale пользователя (доступность) всегда сохраняется.
  // Поверх него добавляется ТОЛЬКО ограниченный ТВ-коэффициент и только когда
  // активен ТВ-интерфейс. На телефонах/планшетах коэффициент = 1.0,
  // т.е. шрифты полностью соответствуют системным настройкам.
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val tvModePreference by RezkaService.tvModePreference.collectAsState()

  val isTvMode = when (tvModePreference) {
    "force_tv" -> true
    "force_mobile" -> false
    else -> TvDetector.isRunningOnTv(context) ||
      configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
  }

  val windowSizeClass = (context as? Activity)?.let { calculateWindowSizeClass(it) }
  val deviceFontScale = if (isTvMode) {
    windowSizeClass?.widthSizeClass?.let { tvScaleForWindowClass(it) }
      ?: tvInterfaceTextScale(configuration.screenWidthDp)
  } else {
    1.0f
  }

  val baseDensity = LocalDensity.current
  val effectiveFontScale = baseDensity.fontScale * deviceFontScale

  androidx.compose.runtime.CompositionLocalProvider(
    com.example.ui.haptics.LocalHapticEngine provides hapticEngine,
    LocalDensity provides Density(baseDensity.density, effectiveFontScale)
  ) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}
