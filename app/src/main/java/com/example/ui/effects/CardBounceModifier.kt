package com.example.ui.effects

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.ui.theme.CinemaPrimary

/**
 * Высокопроизводительный движок ненавязчивого физического подпрыгивания карточки при выборе.
 * Реализует классические принципы Disney/Pixar (Squash & Stretch, Anticipation, Rebound):
 * 1. 0 ALLOCATIONS В КАДРЕ: Все вычисления выполняются на GPU через graphicsLayer,
 *    без перекомпоновки и повторной раскладки (zero relayout).
 * 2. ЖИВАЯ ФИЗИКА:
 *    - 0-160ms: старт с легким растяжением вверх (scaleY = 1.04f, scaleX = 0.98f)
 *    - 160-240ms: парение в верхней точке подпрыгивания (-maxOffsetPx)
 *    - 240-380ms: свободное ускорение вниз под действием гравитации
 *    - 380-440ms: упругое приземление с легким сжатием (squash: scaleY = 0.965f, scaleX = 1.03f)
 *    - 440-540ms: мини-отскок и стабилизация в покое
 * 3. НЕОНОВАЯ АУРА: Мягкое пульсирующее свечение CinemaPrimary в такт фазе прыжка.
 */
fun Modifier.subtleCardBounce(
    isBouncing: Boolean,
    shape: Shape = RoundedCornerShape(12.dp),
    maxElevationOffset: Dp = 8.dp
): Modifier = composed {
    if (!isBouncing) return@composed this

    val infiniteTransition = rememberInfiniteTransition(label = "subtle_card_bounce")

    // Вертикальное смещение (0f -> 1f на пике -> 0f приземление -> 0.22f отскок -> 0f)
    val bounceFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 540
                0f at 0 using FastOutSlowInEasing
                1f at 160 using CubicBezierEasing(0.2f, 0.8f, 0.3f, 1.0f) // Взлет на пик
                0.96f at 240 using FastOutLinearInEasing // Зависание на вершине
                0f at 380 using LinearOutSlowInEasing // Касание поверхности
                0.22f at 440 using FastOutSlowInEasing // Легкий упругий отскок
                0f at 540 // Полное восстановление
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "bounce_y_fraction"
    )

    // Динамика упругости по вертикали (Squash & Stretch Y)
    val scaleY by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 540
                1f at 0
                1.04f at 160 // Вытягивание при взлете
                1.01f at 240 // Парение
                0.965f at 380 // Сжатие при приземлении
                1.015f at 440 // Отскок
                1f at 540
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "bounce_scale_y"
    )

    // Динамика упругости по горизонтали (Squash & Stretch X)
    val scaleX by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 540
                1f at 0
                0.98f at 160 // Сужение при растяжении
                0.995f at 240
                1.03f at 380 // Расширение при сжатии
                0.99f at 440
                1f at 540
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "bounce_scale_x"
    )

    // Пульсирующая подсветка загрузки
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 540
                0.4f at 0
                0.95f at 200
                0.4f at 380
                0.65f at 440
                0.4f at 540
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "bounce_glow_alpha"
    )

    val density = LocalDensity.current
    val maxOffsetPx = remember(density, maxElevationOffset) {
        with(density) { maxElevationOffset.toPx() }
    }

    this
        .zIndex(15f)
        .graphicsLayer {
            translationY = -maxOffsetPx * bounceFraction
            this.scaleX = scaleX
            this.scaleY = scaleY
            shadowElevation = 8f * bounceFraction
        }
        .border(
            width = 1.5.dp,
            color = CinemaPrimary.copy(alpha = glowAlpha),
            shape = shape
        )
}
