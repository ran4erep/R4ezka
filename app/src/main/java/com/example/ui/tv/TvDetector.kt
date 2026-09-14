package com.example.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

enum class TvModePreference(val id: String, val title: String) {
    AUTO("auto", "Автоопределение"),
    FORCE_TV("force_tv", "Интерфейс ТВ (Android TV)"),
    FORCE_MOBILE("force_mobile", "Интерфейс Смартфона / Планшета")
}

/**
 * Высокопроизводительный детектор типа устройства (Smart TV / Android TV приставка / Смартфон).
 * Анализирует системные свойства ОС без лишних аллокаций.
 */
object TvDetector {

    /**
     * Проверяет, является ли текущее устройство телевизором или медиаприставкой.
     */
    fun isRunningOnTv(context: Context): Boolean {
        // 1. Проверка через UiModeManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager != null && uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }

        // 2. Проверка системных фич Leanback / TV
        val packageManager = context.packageManager
        val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        if (hasLeanback) {
            return true
        }

        @Suppress("DEPRECATION")
        val hasTvFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
        if (hasTvFeature) {
            return true
        }

        // 3. Дополнительная эвристика: отсутствие сенсорного экрана при наличии физической клавиатуры/D-Pad
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val hasDpad = packageManager.hasSystemFeature("android.hardware.type.television")

        return !hasTouchScreen && hasDpad
    }

    /**
     * Определение активного режима с учетом пользовательской настройки.
     */
    fun shouldShowTvInterface(context: Context, preference: TvModePreference): Boolean {
        return when (preference) {
            TvModePreference.FORCE_TV -> true
            TvModePreference.FORCE_MOBILE -> false
            TvModePreference.AUTO -> isRunningOnTv(context)
        }
    }
}
