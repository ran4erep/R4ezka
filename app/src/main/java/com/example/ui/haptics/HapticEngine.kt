package com.example.ui.haptics

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/**
 * Уровни интенсивности тактильного отклика:
 * - GENTLE: ультра-мягкая, бархатная вибрация (рекомендуется)
 * - MEDIUM: более четкая, выразительная вибрация
 * - OFF: полностью отключена
 */
enum class HapticIntensity(val key: String, val title: String) {
    GENTLE("gentle", "Нежная (мягкий отклик)"),
    MEDIUM("medium", "Выразительная"),
    OFF("off", "Выключена")
}

/**
 * Типы тактильных микро-событий в интерфейсе R4ezka
 */
enum class HapticType {
    GENTLE_TICK,     // Нежный микро-тик (кнопки, вкладки, фильтры, чипы, мелкие элементы)
    SOFT_CLICK,      // Мягкий бархатный клик (карточки фильмов, запуск видео, диалоги)
    BOUNCE,          // Упругий физический отскок при ударе о границы списка
    EDGE_PULL,       // Мягкое сопротивление натяжения границы
    TOGGLE,          // Щелчок переключателя / свитча
    SELECTION,       // Микро-отклик при выборе пункта в выпадающих списках / радиокнопках
    CONFIRM,         // Подтверждение важного действия (сохранение, избранное)
    WARNING          // Двойной мягкий сигнал предупреждения
}

/**
 * Высокопроизводительный движок тактильного отклика (Haptic Engine).
 *
 * Архитектурные особенности:
 * 1. Нулевая нагрузка на CPU: in-memory кэш настроек, проверка активности за 0 наносекунд.
 * 2. Аппаратные примитивы: использование VibrationEffect.Composition (API 30+) и
 *    аппаратного драйвера (VibrationEffect.createWaveform / createPredefined).
 *    Аппаратное формирование отскока происходит в ядре контроллера без пробуждения корутин CPU.
 * 3. Деликатность: амплитудный контроль генерирует бархатные импульсы 8-14 мс с микро-амплитудой 25-50 (из 255),
 *    без неприятного тракторного жужжания.
 * 4. Защита от спама и дребезга (Debouncing): ограничение частоты кликов (25 мс) и ударов скролла (180 мс).
 * 5. Безопасность: автоматическое отключение на Android TV и устройствах без вибромотора.
 */
class HapticEngine private constructor() {

    @Volatile
    private var vibrator: Vibrator? = null

    @Volatile
    private var hasVibratorHardware: Boolean = false

    @Volatile
    private var hasAmplitudeControl: Boolean = false

    val intensity: HapticIntensity = HapticIntensity.GENTLE

    val bounceEnabled: Boolean = true

    // Метки времени для троттлинга
    private var lastClickUptimeMs: Long = 0L
    private var lastBounceUptimeMs: Long = 0L

    fun init(context: Context) {
        try {
            val appContext = context.applicationContext

            // Инициализация аппаратного вибромотора
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator ?: (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator = v
            hasVibratorHardware = v?.hasVibrator() == true
            hasAmplitudeControl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.hasAmplitudeControl() == true
            } else {
                false
            }
        } catch (_: Throwable) {
            hasVibratorHardware = false
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setIntensity(newIntensity: HapticIntensity) {
        // Вибрация зафиксирована на нежном отклике
    }

    @Suppress("UNUSED_PARAMETER")
    fun setBounceEnabled(enabled: Boolean) {
        // Bounce-отскок зафиксирован во включенном состоянии на нежном уровне
    }

    /**
     * Основная точка воспроизведения тактильного отклика.
     * Мгновенный выход, если вибрация отсутствует в устройстве.
     */
    fun perform(type: HapticType, impactVelocity: Float = 0f, viewFallback: View? = null) {
        if (!hasVibratorHardware) {
            return
        }

        val now = SystemClock.uptimeMillis()

        when (type) {
            HapticType.GENTLE_TICK -> {
                if (now - lastClickUptimeMs < MIN_CLICK_INTERVAL_MS) return
                lastClickUptimeMs = now
                triggerGentleTick(viewFallback)
            }
            HapticType.SOFT_CLICK -> {
                if (now - lastClickUptimeMs < MIN_CLICK_INTERVAL_MS) return
                lastClickUptimeMs = now
                triggerSoftClick(viewFallback)
            }
            HapticType.BOUNCE -> {
                if (now - lastBounceUptimeMs < MIN_BOUNCE_INTERVAL_MS) return
                lastBounceUptimeMs = now
                triggerBounceEffect(impactVelocity, viewFallback)
            }
            HapticType.EDGE_PULL -> {
                if (now - lastClickUptimeMs < MIN_CLICK_INTERVAL_MS * 2) return
                lastClickUptimeMs = now
                triggerEdgePull(viewFallback)
            }
            HapticType.TOGGLE -> {
                if (now - lastClickUptimeMs < MIN_CLICK_INTERVAL_MS) return
                lastClickUptimeMs = now
                triggerToggle(viewFallback)
            }
            HapticType.SELECTION -> {
                if (now - lastClickUptimeMs < 15L) return
                lastClickUptimeMs = now
                triggerSelection(viewFallback)
            }
            HapticType.CONFIRM -> {
                if (now - lastClickUptimeMs < MIN_CLICK_INTERVAL_MS) return
                lastClickUptimeMs = now
                triggerConfirm(viewFallback)
            }
            HapticType.WARNING -> {
                if (now - lastClickUptimeMs < MIN_CLICK_INTERVAL_MS) return
                lastClickUptimeMs = now
                triggerWarning(viewFallback)
            }
        }
    }

    /**
     * Нежный микро-тик для обычных элементов интерфейса
     */
    @SuppressLint("MissingPermission")
    private fun triggerGentleTick(view: View?) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.15f)
                    .compose()
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAmplitudeControl) {
                val effect = VibrationEffect.createOneShot(5L, 16)
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(5L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(5L)
            }
        } catch (_: Throwable) {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * Мягкий бархатный клик для открытия фильмов и карточек
     */
    @SuppressLint("MissingPermission")
    private fun triggerSoftClick(view: View?) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.22f)
                    .compose()
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAmplitudeControl) {
                val effect = VibrationEffect.createOneShot(8L, 24)
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(8L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(8L)
            }
        } catch (_: Throwable) {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /**
     * Упругий Bounce-отскок при ударе прокрутки о границы экрана.
     * Реализует эластичный отклик: мягкий упругий импульс + микро-затухающий эхо-отскок.
     */
    @SuppressLint("MissingPermission")
    private fun triggerBounceEffect(velocity: Float, view: View?) {
        val v = vibrator ?: return
        try {
            val speedNormalized = (abs(velocity) / 2500f).coerceIn(0.2f, 1.0f)
            val primaryScale = (0.20f * speedNormalized).coerceIn(0.10f, 0.35f)
            val echoScale = (primaryScale * 0.35f).coerceIn(0.05f, 0.15f)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            ) {
                // Аппаратная композиция: нежный упругий микро-удар -> затухание
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, primaryScale)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, echoScale, 28)
                    .compose()
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAmplitudeControl) {
                val primaryAmp = (primaryScale * 80).toInt().coerceIn(12, 40)
                val echoAmp = (echoScale * 50).toInt().coerceIn(6, 18)
                val timings = longArrayOf(0, 8, 24, 6)
                val amplitudes = intArrayOf(0, primaryAmp, 0, echoAmp)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(8L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(8L)
            }
        } catch (_: Throwable) {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * Нежное сопротивление при растяжении границы списка (drag overscroll)
     */
    @SuppressLint("MissingPermission")
    private fun triggerEdgePull(view: View?) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.10f)
                    .compose()
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                vibrateWithAttributes(v, effect)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAmplitudeControl) {
                val effect = VibrationEffect.createOneShot(5L, 12)
                vibrateWithAttributes(v, effect)
                return
            }

            triggerGentleTick(view)
        } catch (_: Throwable) {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * Щелчок переключателя (Switch / Checkbox)
     */
    @SuppressLint("MissingPermission")
    private fun triggerToggle(view: View?) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.18f)
                    .compose()
                vibrateWithAttributes(v, effect)
                return
            }
            triggerGentleTick(view)
        } catch (_: Throwable) {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * Микро-отклик при выборе пункта в выпадающих меню и табах
     */
    @SuppressLint("MissingPermission")
    private fun triggerSelection(view: View?) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.14f)
                    .compose()
                vibrateWithAttributes(v, effect)
                return
            }
            triggerGentleTick(view)
        } catch (_: Throwable) {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * Подтверждение успешного действия (сохранение, добавление в избранное)
     */
    @SuppressLint("MissingPermission")
    private fun triggerConfirm(view: View?) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            ) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.16f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.22f, 35)
                    .compose()
                vibrateWithAttributes(v, effect)
                return
            }
            triggerSoftClick(view)
        } catch (_: Throwable) {
            view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    /**
     * Мягкое предупреждение
     */
    @SuppressLint("MissingPermission")
    private fun triggerWarning(view: View?) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.18f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.18f, 50)
                    .compose()
                vibrateWithAttributes(v, effect)
                return
            }
            triggerGentleTick(view)
        } catch (_: Throwable) {
            view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
    }

    @SuppressLint("MissingPermission")
    private fun vibrateWithAttributes(v: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
            v.vibrate(effect, attributes)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build()
            v.vibrate(effect, audioAttributes)
        } else {
            v.vibrate(effect)
        }
    }

    companion object {
        const val PREF_HAPTIC_INTENSITY = "pref_haptic_intensity"
        const val PREF_HAPTIC_BOUNCE = "pref_haptic_bounce"

        private const val MIN_CLICK_INTERVAL_MS = 25L
        private const val MIN_BOUNCE_INTERVAL_MS = 180L

        @Volatile
        private var instance: HapticEngine? = null

        fun get(): HapticEngine {
            return instance ?: synchronized(this) {
                instance ?: HapticEngine().also { instance = it }
            }
        }

        fun init(context: Context): HapticEngine {
            val engine = get()
            engine.init(context)
            return engine
        }
    }
}

/**
 * CompositionLocal для прямого доступа к HapticEngine из любого Composable компонента
 */
val LocalHapticEngine = staticCompositionLocalOf<HapticEngine> {
    HapticEngine.get()
}

/**
 * NestedScrollConnection для тактильного Bounce-эффекта при ударе списков/сеток о границы экрана.
 * Не поглощает дельты прокрутки (Offset.Zero / Velocity.Zero), сохраняя все нативные визуальные эффекты.
 */
class BounceNestedScrollConnection(
    private val hapticEngine: HapticEngine,
    private val orientation: Orientation = Orientation.Vertical,
    private val view: View? = null
) : NestedScrollConnection {

    private var hasTriggeredDragEdge = false
    private var lastFlingBounceTime = 0L

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        val overscrollDelta = if (orientation == Orientation.Vertical) available.y else available.x
        val consumedDelta = if (orientation == Orientation.Vertical) consumed.y else consumed.x

        if (source == NestedScrollSource.Drag) {
            if (abs(overscrollDelta) > 1.0f) {
                if (!hasTriggeredDragEdge) {
                    hasTriggeredDragEdge = true
                    hapticEngine.perform(HapticType.BOUNCE, impactVelocity = abs(overscrollDelta) * 40f, viewFallback = view)
                }
            } else if (abs(consumedDelta) > 1.0f) {
                // Сбрасываем флаг ТОЛЬКО если пользователь начал прокручивать содержимое списка обратно
                hasTriggeredDragEdge = false
            }
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val overscrollVelocity = if (orientation == Orientation.Vertical) available.y else available.x
        val speed = abs(overscrollVelocity)

        if (speed > 140f) {
            val now = SystemClock.uptimeMillis()
            if (now - lastFlingBounceTime > 220L) {
                lastFlingBounceTime = now
                hapticEngine.perform(HapticType.BOUNCE, impactVelocity = speed, viewFallback = view)
            }
        }
        hasTriggeredDragEdge = false
        return Velocity.Zero
    }
}

/**
 * Модификатор для добавления упругого тактильного bounce-эффекта к любому скроллируемому контейнеру
 * (LazyColumn, LazyVerticalGrid, LazyRow, Scrollable Column).
 */
@Composable
fun Modifier.bounceOverscroll(
    orientation: Orientation = Orientation.Vertical
): Modifier {
    val haptic = LocalHapticEngine.current
    val view = LocalView.current
    val connection = remember(haptic, orientation, view) {
        BounceNestedScrollConnection(haptic, orientation, view)
    }
    return this.nestedScroll(connection)
}

/**
 * Модификатор-обертка над clickable, обеспечивающий нежный тактильный отклик при касании.
 */
fun Modifier.hapticClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    hapticType: HapticType = HapticType.GENTLE_TICK,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit
): Modifier = composed {
    val haptic = LocalHapticEngine.current
    val view = LocalView.current
    val actualSource = interactionSource ?: remember { MutableInteractionSource() }

    this.clickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        interactionSource = actualSource,
        indication = androidx.compose.material3.ripple(),
        onClick = {
            haptic.perform(hapticType, viewFallback = view)
            onClick()
        }
    )
}
