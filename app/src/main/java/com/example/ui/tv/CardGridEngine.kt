package com.example.ui.tv

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.RezkaService

/**
 * Результат расчета параметров сетки карточек.
 */
data class ResolvedCardGrid(
    val columns: Int,
    val rows: Int,
    val cardHeight: Dp,
    val isExactRowsFitted: Boolean,
    val horizontalSpacing: Dp,
    val verticalSpacing: Dp,
    val estimatedCardWidth: Dp = Dp.Unspecified
)

/**
 * Высокопроизводительный движок вычисления геометрии сетки карточек фильмов/сериалов.
 * 
 * Особенности:
 * 1. В ландшафтной ориентации телефона и на телевизоре карточки подстраиваются по высоте
 *    так, чтобы на видимом экране помещалось ровно целое число рядов (без обрезания нижнего ряда).
 * 2. Для портретной (вертикальной) ориентации телефона сохраняется привычный естественный вид
 *    со стандартными пропорциями постера 2:3 и свободным вертикальным скроллингом.
 */
object CardGridEngine {

    fun calculate(
        cardGridMode: String,
        availableWidth: Dp,
        availableHeight: Dp,
        isLandscapeOrTv: Boolean
    ): ResolvedCardGrid {
        val parsed = RezkaService.parseCardGrid(cardGridMode)

        // Вертикальный телефон: оставляем стандартное поведение со свободным вертикальным скроллом
        if (!isLandscapeOrTv) {
            val cols = parsed?.columns ?: 2
            val hSpacing = if (cols >= 6) 8.dp else 14.dp
            val vSpacing = if (cols >= 6) 10.dp else 16.dp
            val totalHSpacing = (hSpacing.value * (cols - 1)).dp
            val estWidth = if (availableWidth > 0.dp) {
                ((availableWidth - totalHSpacing).value / cols.coerceAtLeast(1)).dp
            } else {
                Dp.Unspecified
            }
            return ResolvedCardGrid(
                columns = cols,
                rows = parsed?.rows ?: 1,
                cardHeight = Dp.Unspecified,
                isExactRowsFitted = false,
                horizontalSpacing = hSpacing,
                verticalSpacing = vSpacing,
                estimatedCardWidth = estWidth
            )
        }

        // Ландшафтная ориентация (телефон) или ТВ: карточки ДОЛЖНЫ быть по высоте целиком!
        val targetCols: Int
        val targetRows: Int

        if (parsed != null) {
            // Явно заданная конфигурация сетки (например 10x5, 5x2, 4x2, 10x2 и т.д.)
            targetCols = parsed.columns
            targetRows = parsed.rows
        } else {
            // Режим "Авто": автоматический расчет под экран, чтобы ряды были видны целиком
            targetRows = when {
                availableHeight < 330.dp -> 1
                availableHeight > 680.dp -> 3
                else -> 2
            }
            // Пропорции карточки (постер + заголовок снизу)
            val approxCardHeight = (availableHeight / targetRows).coerceAtLeast(60.dp)
            val approxCardWidth = (approxCardHeight * 0.64f).coerceAtLeast(50.dp)
            val calculatedCols = ((availableWidth + 10.dp) / (approxCardWidth + 10.dp)).toInt()
            targetCols = calculatedCols.coerceIn(3, 10)
        }

        // Отступы между карточками
        val hSpacing = if (targetCols >= 8) 6.dp else if (targetCols >= 6) 8.dp else 12.dp
        val vSpacing = if (targetRows >= 5) 4.dp else if (targetRows >= 4) 6.dp else if (targetRows >= 3) 8.dp else 12.dp

        // Вычисляем точную высоту карточки, чтобы ровно targetRows рядов поместились в availableHeight
        val totalVerticalSpacings = (vSpacing.value * (targetRows - 1)).dp
        val minAllowedHeight = (40f * targetRows).dp
        val netHeight = (availableHeight - totalVerticalSpacings).coerceAtLeast(minAllowedHeight)
        val calculatedCardHeight = (netHeight.value / targetRows).dp

        val totalHorizontalSpacings = (hSpacing.value * (targetCols - 1)).dp
        val netWidth = (availableWidth - totalHorizontalSpacings).coerceAtLeast((30f * targetCols).dp)
        val calculatedCardWidth = (netWidth.value / targetCols).dp

        return ResolvedCardGrid(
            columns = targetCols,
            rows = targetRows,
            cardHeight = calculatedCardHeight,
            isExactRowsFitted = true,
            horizontalSpacing = hSpacing,
            verticalSpacing = vSpacing,
            estimatedCardWidth = calculatedCardWidth
        )
    }
}
