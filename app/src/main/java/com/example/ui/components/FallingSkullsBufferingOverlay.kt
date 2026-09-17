package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import com.example.ui.theme.CinemaPrimary
import com.example.ui.theme.CinemaTextWhite
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.max
import kotlin.random.Random

private class SkullParticle(
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var rotation: Float = 0f,
    var vRot: Float = 0f,
    var radius: Float = 0f,
    var alpha: Float = 1f
) {
    fun initRandom(screenWidthPx: Float, screenHeightPx: Float, density: Float) {
        val sizeDp = Random.nextFloat() * 12f + 22f // 22dp to 34dp
        radius = (sizeDp * density) / 2f
        x = Random.nextFloat() * screenWidthPx
        y = Random.nextFloat() * screenHeightPx

        val speedDp = Random.nextFloat() * 35f + 20f // 20 to 55 dp/sec speed
        val speedPx = speedDp * density
        
        // Преобладающий поток слева направо (углы от -35° до +35°) для красивого перелета через весь широкий ландшафтный экран
        val angleDeg = Random.nextFloat() * 70f - 35f
        val angleRad = angleDeg * (Math.PI.toFloat() / 180f)
        vx = kotlin.math.cos(angleRad) * speedPx
        vy = kotlin.math.sin(angleRad) * speedPx

        rotation = Random.nextFloat() * 360f
        vRot = (Random.nextFloat() - 0.5f) * 36f // Медленное плавное вращение
        alpha = Random.nextFloat() * 0.45f + 0.45f
    }
}

/**
 * Высокопроизводительный оверлей буферизации с плавно плывущими черепками.
 * Черепки плывут по экрану с рандомным вектором движения и плавным вращением.
 */
@Composable
fun FallingSkullsBufferingOverlay(
    modifier: Modifier = Modifier,
    text: String = "Буферизация... Приятного просмотра!"
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density

    // Кэшированная растризация skull_vector.svg из assets
    val skullBitmap = remember(context, density) {
        try {
            val svgContent = context.assets.open("skull_vector.svg").bufferedReader().use { it.readText() }
            val match = Regex("""d=["']([^"']+)["']""").find(svgContent)
            val pathData = match?.groupValues?.get(1) ?: ""
            if (pathData.isNotEmpty()) {
                val nativePath = PathParser.createPathFromPathData(pathData)
                val targetSizePx = (32f * density).toInt().coerceAtLeast(32)
                val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)

                val bounds = RectF()
                nativePath.computeBounds(bounds, true)
                val w = max(bounds.width(), 1f)
                val h = max(bounds.height(), 1f)
                val scale = (targetSizePx.toFloat() * 0.88f) / max(w, h)

                val matrix = android.graphics.Matrix()
                matrix.postTranslate(-bounds.left, -bounds.top)
                matrix.postScale(scale, scale)
                matrix.postTranslate((targetSizePx - w * scale) / 2f, (targetSizePx - h * scale) / 2f)

                val transformedPath = android.graphics.Path()
                nativePath.transform(matrix, transformedPath)

                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawPath(transformedPath, paint)
                bitmap.asImageBitmap()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (skullBitmap != null) {
            SkullsParticleCanvas(
                skullBitmap = skullBitmap,
                density = density,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            CircularProgressIndicator(
                color = CinemaPrimary,
                modifier = Modifier.size(56.dp)
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = text,
                color = CinemaTextWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Изолированный слой анимации частиц черепков для нулевой лишней нагрузки на CPU и исключения рекомпозиций родительского дерева UI.
 */
@Composable
private fun SkullsParticleCanvas(
    skullBitmap: androidx.compose.ui.graphics.ImageBitmap,
    density: Float,
    modifier: Modifier = Modifier
) {
    var canvasWidth by remember { mutableFloatStateOf(0f) }
    var canvasHeight by remember { mutableFloatStateOf(0f) }

    val numParticles = 48
    val particles = remember { Array(numParticles) { SkullParticle() } }
    var tick by remember { mutableLongStateOf(0L) }

    var prevWidth by remember { mutableFloatStateOf(0f) }
    var prevHeight by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(canvasWidth, canvasHeight) {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return@LaunchedEffect

        if (prevWidth <= 0f || prevHeight <= 0f) {
            // Первичная инициализация
            particles.forEach { p ->
                p.initRandom(canvasWidth, canvasHeight, density)
            }
        } else if (prevWidth != canvasWidth || prevHeight != canvasHeight) {
            // Масштабирование координат при смене ориентации экранов (портрет -> ландшафт)
            val scaleX = canvasWidth / prevWidth
            val scaleY = canvasHeight / prevHeight
            particles.forEach { p ->
                p.x = (p.x * scaleX).coerceIn(0f, canvasWidth)
                p.y = (p.y * scaleY).coerceIn(0f, canvasHeight)
            }
        }
        prevWidth = canvasWidth
        prevHeight = canvasHeight

        var lastNanos = System.nanoTime()
        while (true) {
            awaitFrame()
            val now = System.nanoTime()
            val dt = ((now - lastNanos) / 1e9f).coerceIn(0.005f, 0.05f)
            lastNanos = now

            particles.forEach { p ->
                p.x += p.vx * dt
                p.y += p.vy * dt
                p.rotation = (p.rotation + p.vRot * dt) % 360f

                val margin = p.radius * 3.0f + 16f * density

                // Перенос черепков с вылетом за противоположные границы экрана
                if (p.x > canvasWidth + margin) {
                    p.x = -margin
                    p.y = Random.nextFloat() * canvasHeight
                } else if (p.x < -margin) {
                    p.x = canvasWidth + margin
                    p.y = Random.nextFloat() * canvasHeight
                }

                if (p.y > canvasHeight + margin) {
                    p.y = -margin
                    p.x = Random.nextFloat() * canvasWidth
                } else if (p.y < -margin) {
                    p.y = canvasHeight + margin
                    p.x = Random.nextFloat() * canvasWidth
                }
            }

            tick++
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                canvasWidth = coords.size.width.toFloat()
                canvasHeight = coords.size.height.toFloat()
            }
    ) {
        @Suppress("UNUSED_VARIABLE")
        val frame = tick
        val bmp = skullBitmap
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        val halfBmpW = (bmpW / 2f).toInt()
        val halfBmpH = (bmpH / 2f).toInt()

        particles.forEach { p ->
            if (p.x + p.radius >= 0f && p.x - p.radius <= size.width &&
                p.y + p.radius >= 0f && p.y - p.radius <= size.height) {
                withTransform({
                    translate(left = p.x, top = p.y)
                    rotate(degrees = p.rotation)
                }) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(-halfBmpW, -halfBmpH),
                        alpha = p.alpha
                    )
                }
            }
        }
    }
}
