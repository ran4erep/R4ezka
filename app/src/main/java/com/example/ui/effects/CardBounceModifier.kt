package com.example.ui.effects

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Высокопроизводительный движок медленного, ненавязчивого и нежного парения карточки при выборе.
 *
 * Оптимизация и архитектура:
 * 1. 0 ALLOCATIONS В КАДРЕ: Все вычисления выполняются напрямую на GPU через graphicsLayer,
 *    исключая рекомпозицию дочерних элементов и повторную раскладку (zero relayout / zero recomposition).
 * 2. НЕЖНАЯ БИОНИЧЕСКАЯ ФИЗИКА:
 *    - Плавное гармоническое парение (цикл ~1040ms) с мягким замедлением в верхней точке.
 *    - Деликатный микро-масштаб (1.0f -> 1.018f), создающий эффект естественного "дыхания".
 *    - Мягкая ненавязчивая тень, углубляющаяся пропорционально подъему.
 *    - Полное отсутствие акцентных рамок, отвлекающих пользователя.
 */
fun Modifier.subtleCardBounce(
    isBouncing: Boolean,
    shape: Shape = RoundedCornerShape(12.dp),
    maxElevationOffset: Dp = 5.dp
): Modifier = composed {
    if (!isBouncing) return@composed this

    val infiniteTransition = rememberInfiniteTransition(label = "subtle_card_float")

    // Плавное гармоническое смещение вверх и вниз (мягкое синусоидальное парение)
    val floatFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 520,
                easing = CubicBezierEasing(0.37f, 0.0f, 0.23f, 1.0f)
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float_fraction"
    )

    // Едва уловимое дыхание карточки (микро-приближение к зрителю без искажения пропорций)
    val scaleFraction by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.018f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 520,
                easing = CubicBezierEasing(0.37f, 0.0f, 0.23f, 1.0f)
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale_fraction"
    )

    val density = LocalDensity.current
    val maxOffsetPx = remember(density, maxElevationOffset) {
        with(density) { maxElevationOffset.toPx() }
    }

    this
        .zIndex(15f)
        .graphicsLayer {
            translationY = -maxOffsetPx * floatFraction
            scaleX = scaleFraction
            scaleY = scaleFraction
            shadowElevation = 6f * floatFraction
            this.shape = shape
            clip = false
        }
}

