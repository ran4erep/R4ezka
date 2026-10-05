package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.isActive
import java.util.Calendar
import kotlin.math.sin
import kotlin.random.Random

/**
 * Режимы работы зимнего снегопада.
 */
enum class SnowMode(val id: String, val title: String, val subtitle: String) {
    ALWAYS_ON(
        id = "always",
        title = "Включен постоянно (тест)",
        subtitle = "Снегопад активен всегда для предварительного просмотра и настройки"
    ),
    SEASONAL(
        id = "seasonal",
        title = "Зимой (20 дек — 31 янв)",
        subtitle = "Включается автоматически в зимний праздничный период с 20 декабря до конца января"
    ),
    DISABLED(
        id = "disabled",
        title = "Выключен",
        subtitle = "Снегопад полностью отключен"
    );

    companion object {
        fun fromId(id: String?): SnowMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ALWAYS_ON
        }
    }
}

/**
 * Высокопроизводительный движок атмосферных сезонных эффектов.
 * Нулевые аллокации памяти в цикле рендеринга (Zero Allocations).
 * Минимальная нагрузка на CPU / GPU благодаря отрисовке на одном Canvas
 * и привязке к VSYNC частоте экрана.
 */
object SnowfallEngine {

    /**
     * Проверка, попадает ли текущая дата в зимний сезон (20 декабря - 31 января включительно).
     */
    fun isWinterSeason(calendar: Calendar = Calendar.getInstance()): Boolean {
        val month = calendar.get(Calendar.MONTH) // Calendar.DECEMBER = 11, Calendar.JANUARY = 0
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return (month == Calendar.DECEMBER && day >= 20) || (month == Calendar.JANUARY && day <= 31)
    }

    /**
     * Определение активности эффекта снегопада на основе режима и календаря.
     */
    fun shouldRender(mode: SnowMode, calendar: Calendar = Calendar.getInstance()): Boolean {
        return when (mode) {
            SnowMode.ALWAYS_ON -> true
            SnowMode.SEASONAL -> isWinterSeason(calendar)
            SnowMode.DISABLED -> false
        }
    }
}

/**
 * Буфер частиц с плоскими примитивными массивами.
 * Гарантирует отсутствие работы сборщика мусора (GC) во время снегопада.
 */
private class SnowParticlePool(val capacity: Int) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val baseX = FloatArray(capacity)
    val radius = FloatArray(capacity)
    val speedY = FloatArray(capacity)
    val swingAmp = FloatArray(capacity)
    val swingSpeed = FloatArray(capacity)
    val alpha = FloatArray(capacity)
    val phase = FloatArray(capacity)

    var isInitialized = false
    private var lastW = 0f
    private var lastH = 0f

    fun initIfNeeded(width: Float, height: Float, density: Float) {
        if (width <= 0f || height <= 0f) return
        if (isInitialized && width == lastW && height == lastH) return

        lastW = width
        lastH = height
        isInitialized = true

        val pi2 = (Math.PI * 2.0).toFloat()

        for (i in 0 until capacity) {
            // Распределение по 3 слоям глубины (Parallax / Depth of field)
            val depthLayer = when {
                i < capacity * 0.45f -> 0 // Мелкие дальние снежинки
                i < capacity * 0.82f -> 1 // Средний план
                else -> 2                // Крупный передний план
            }

            when (depthLayer) {
                0 -> {
                    radius[i] = (Random.nextFloat() * 0.7f + 1.1f) * density
                    speedY[i] = (Random.nextFloat() * 20f + 25f) * density
                    alpha[i] = Random.nextFloat() * 0.22f + 0.25f
                    swingAmp[i] = (Random.nextFloat() * 8f + 8f) * density
                    swingSpeed[i] = Random.nextFloat() * 1.2f + 1.0f
                }
                1 -> {
                    radius[i] = (Random.nextFloat() * 0.9f + 1.8f) * density
                    speedY[i] = (Random.nextFloat() * 25f + 45f) * density
                    alpha[i] = Random.nextFloat() * 0.25f + 0.45f
                    swingAmp[i] = (Random.nextFloat() * 12f + 14f) * density
                    swingSpeed[i] = Random.nextFloat() * 1.4f + 1.4f
                }
                else -> {
                    radius[i] = (Random.nextFloat() * 1.1f + 2.7f) * density
                    speedY[i] = (Random.nextFloat() * 35f + 65f) * density
                    alpha[i] = Random.nextFloat() * 0.25f + 0.65f
                    swingAmp[i] = (Random.nextFloat() * 16f + 18f) * density
                    swingSpeed[i] = Random.nextFloat() * 1.6f + 1.8f
                }
            }

            baseX[i] = Random.nextFloat() * width
            y[i] = Random.nextFloat() * height
            phase[i] = Random.nextFloat() * pi2
            x[i] = baseX[i] + sin(phase[i]) * swingAmp[i]
        }
    }

    fun update(dt: Float) {
        if (!isInitialized || lastW <= 0f || lastH <= 0f) return
        val pi2 = (Math.PI * 2.0).toFloat()
        val width = lastW
        val height = lastH

        for (i in 0 until capacity) {
            phase[i] += dt * swingSpeed[i]
            if (phase[i] > pi2) phase[i] -= pi2

            x[i] = baseX[i] + sin(phase[i]) * swingAmp[i]
            y[i] += speedY[i] * dt

            // Мягкий перенос за верхнюю границу при выходе за пределы экрана
            if (y[i] > height + radius[i] + 4f) {
                y[i] = -radius[i] - (Random.nextFloat() * 20f)
                baseX[i] = Random.nextFloat() * width
                phase[i] = Random.nextFloat() * pi2
            }
        }
    }
}

/**
 * Ультра-легкий оверлей падающего снега.
 * Полностью прозрачен для кликов, жестов и касаний (не мешает навигации).
 * Автоматически останавливается при открытии видеоплеера для сохранения ресурсов процессора.
 */
@Composable
fun SnowfallOverlay(
    mode: SnowMode,
    isPlayerActive: Boolean,
    modifier: Modifier = Modifier,
    snowflakeCount: Int = 60
) {
    val shouldRender = remember(mode) { SnowfallEngine.shouldRender(mode) }
    if (!shouldRender || isPlayerActive) return

    val density = LocalDensity.current.density
    val pool = remember(snowflakeCount) { SnowParticlePool(snowflakeCount) }

    // Тактирование кадров через VSYNC
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isPlayerActive) {
        var lastFrameNanos = 0L
        while (isActive && !isPlayerActive) {
            awaitFrame()
            val currentNanos = System.nanoTime()
            if (lastFrameNanos > 0L) {
                val dt = ((currentNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                pool.update(dt)
                frameTick++
            }
            lastFrameNanos = currentNanos
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        pool.initIfNeeded(w, h, density)

        // Чтение frameTick обеспечивает перерисовку каждого кадра
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTick

        val cap = pool.capacity
        for (i in 0 until cap) {
            val px = pool.x[i]
            val py = pool.y[i]
            val r = pool.radius[i]
            val a = pool.alpha[i]

            // Отрисовка мягкой снежинки
            drawCircle(
                color = Color.White.copy(alpha = a),
                radius = r,
                center = Offset(px, py)
            )
        }
    }
}
