package com.example.ui.effects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import java.util.Calendar
import kotlin.math.sin
import kotlin.random.Random

/**
 * Высокопроизводительный движок частиц падающего снега (Snowfall Engine).
 * Архитектурные свойства минимальной нагрузки на CPU/GPU:
 * 1. ZERO GC ALLOCATION: Все частицы хранятся в непрерывных примитивных массивах FloatArray.
 *    0 аллокаций объектов в цикле отрисовки (60/90/120 FPS).
 * 2. NO RECOMPOSITION LOOP: Анимация кадров запускается через withFrameNanos и обновляет
 *    исключительно фазу отрисовки Canvas DrawScope, полностью исключая рекомпозицию UI-дерева Compose.
 * 3. 3-LAYER CINEMATIC DEPTH:
 *    - Far layer (дальний план): мелкие снежинки, медленный дрейф, легкая прозрачность.
 *    - Mid layer (средний план): синусоидальное покачивание и умеренная плотность.
 *    - Near layer (ближний план): крупные пушистые снежинки с высокой скоростью.
 * 4. ПОЛНЫЙ СОН В ВИДЕОПЛЕЕРЕ: При воспроизведении фильма цикл мгновенно приостанавливается (0% CPU).
 * 5. АВТОМАТИЧЕСКАЯ СЕЗОННОСТЬ: Автоматически активен с 20 декабря до 31 января включительно.
 */
class SnowfallState(
    val particleCount: Int,
    private val densityDpiScale: Float
) {
    // Непрерывные примитивные массивы (Flat arrays) — 0 аллокаций при обновлении
    private val x = FloatArray(particleCount)
    private val y = FloatArray(particleCount)
    private val radius = FloatArray(particleCount)
    private val speedY = FloatArray(particleCount)
    private val swaySpeed = FloatArray(particleCount)
    private val swayAmplitude = FloatArray(particleCount)
    private val swayPhase = FloatArray(particleCount)
    private val alpha = FloatArray(particleCount)

    private var isInitialized = false
    private var lastWidth = 0f
    private var lastHeight = 0f
    private var globalWindTime = 0f

    fun initialize(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        lastWidth = width
        lastHeight = height

        val random = Random(System.currentTimeMillis())

        for (i in 0 until particleCount) {
            val layerRatio = i.toFloat() / particleCount.toFloat()
            val layer = when {
                layerRatio < 0.50f -> 0 // 50% дальний фон
                layerRatio < 0.85f -> 1 // 35% средний план
                else -> 2               // 15% ближний план (крупные хлопья)
            }

            x[i] = random.nextFloat() * width
            y[i] = random.nextFloat() * height

            when (layer) {
                0 -> { // Дальний слой
                    radius[i] = (1.0f + random.nextFloat() * 1.2f) * densityDpiScale
                    speedY[i] = (18f + random.nextFloat() * 20f) * densityDpiScale
                    swaySpeed[i] = 1.0f + random.nextFloat() * 1.5f
                    swayAmplitude[i] = (8f + random.nextFloat() * 12f) * densityDpiScale
                    alpha[i] = 0.22f + random.nextFloat() * 0.22f
                }
                1 -> { // Средний слой
                    radius[i] = (2.2f + random.nextFloat() * 1.4f) * densityDpiScale
                    speedY[i] = (36f + random.nextFloat() * 32f) * densityDpiScale
                    swaySpeed[i] = 1.5f + random.nextFloat() * 2.0f
                    swayAmplitude[i] = (14f + random.nextFloat() * 18f) * densityDpiScale
                    alpha[i] = 0.45f + random.nextFloat() * 0.28f
                }
                else -> { // Ближний слой
                    radius[i] = (3.8f + random.nextFloat() * 2.2f) * densityDpiScale
                    speedY[i] = (65f + random.nextFloat() * 45f) * densityDpiScale
                    swaySpeed[i] = 2.0f + random.nextFloat() * 2.5f
                    swayAmplitude[i] = (20f + random.nextFloat() * 24f) * densityDpiScale
                    alpha[i] = 0.70f + random.nextFloat() * 0.25f
                }
            }

            swayPhase[i] = random.nextFloat() * 6.2831853f // [0, 2*PI]
        }
        isInitialized = true
    }

    var viewportWidth = 0f
    var viewportHeight = 0f

    fun update(deltaTimeSec: Float) {
        val width = viewportWidth
        val height = viewportHeight
        if (width <= 0f || height <= 0f) return

        if (!isInitialized || width != lastWidth || height != lastHeight) {
            initialize(width, height)
            return
        }

        globalWindTime += deltaTimeSec * 0.4f
        val globalWind = sin(globalWindTime) * (12f * densityDpiScale)

        val random = Random

        for (i in 0 until particleCount) {
            y[i] += speedY[i] * deltaTimeSec

            swayPhase[i] += swaySpeed[i] * deltaTimeSec
            val currentSway = sin(swayPhase[i]) * swayAmplitude[i] * deltaTimeSec
            x[i] += currentSway + (globalWind * deltaTimeSec)

            if (y[i] > height + radius[i] * 2) {
                y[i] = -radius[i] * 2
                x[i] = random.nextFloat() * width
            } else if (y[i] < -radius[i] * 4) {
                y[i] = height + radius[i]
            }

            if (x[i] > width + radius[i] * 2) {
                x[i] = -radius[i]
            } else if (x[i] < -radius[i] * 2) {
                x[i] = width + radius[i]
            }
        }
    }

    fun draw(drawScope: DrawScope) {
        if (!isInitialized) return
        with(drawScope) {
            for (i in 0 until particleCount) {
                drawCircle(
                    color = Color.White.copy(alpha = alpha[i]),
                    radius = radius[i],
                    center = androidx.compose.ui.geometry.Offset(x[i], y[i])
                )
            }
        }
    }

    companion object {
        /**
         * Проверка новогоднего зимнего сезона (с 20 декабря до 31 января включительно).
         */
        fun isWinterSeason(calendar: Calendar = Calendar.getInstance()): Boolean {
            val month = calendar.get(Calendar.MONTH) // Calendar.JANUARY = 0, Calendar.DECEMBER = 11
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            return when (month) {
                Calendar.DECEMBER -> day >= 20
                Calendar.JANUARY -> day <= 31
                else -> false
            }
        }
    }
}

/**
 * Оптимизированный оверлей падающего снега.
 * Автоматически включается строго с 20-го декабря до конца января.
 * Засыпает при открытии видеоплеера, не мешает кликам.
 */
@Composable
fun SnowfallOverlay(
    isPlayerActive: Boolean,
    isTvMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isSeason = remember { SnowfallState.isWinterSeason() }
    val shouldShow = isSeason && !isPlayerActive

    if (!shouldShow) return

    val density = LocalDensity.current.density
    val particleCount = if (isTvMode) 75 else 50

    val snowfallState = remember(particleCount, density) {
        SnowfallState(
            particleCount = particleCount,
            densityDpiScale = density
        )
    }

    val frameTick = remember { mutableLongStateOf(0L) }

    LaunchedEffect(shouldShow) {
        var lastFrameNanos = 0L
        while (shouldShow) {
            withFrameNanos { currentFrameNanos ->
                if (lastFrameNanos != 0L) {
                    val dt = ((currentFrameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    snowfallState.update(dt)
                }
                lastFrameNanos = currentFrameNanos
                frameTick.longValue = currentFrameNanos
            }
        }
    }

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTick.longValue

        snowfallState.viewportWidth = size.width
        snowfallState.viewportHeight = size.height
        snowfallState.draw(this)
    }
}
