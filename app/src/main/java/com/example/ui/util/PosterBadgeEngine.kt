package com.example.ui.util

import androidx.collection.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.RezkaService
import com.example.data.RezkaType
import com.example.ui.theme.CinemaPrimary
import com.example.ui.theme.CinemaTextWhite

/**
 * Режим отображения плашек на постерах сериалов.
 */
enum class SeriesBadgeMode(
    val id: String,
    val title: String
) {
    ADAPTIVE(
        id = "adaptive",
        title = "Адаптивные"
    ),
    COMPACT(
        id = "compact",
        title = "Всегда кратко"
    ),
    ORIGINAL(
        id = "original",
        title = "Как на сайте"
    ),
    DISABLED(
        id = "disabled",
        title = "Отключены"
    );

    companion object {
        fun fromId(id: String?): SeriesBadgeMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ADAPTIVE
        }
    }
}

/**
 * Предварительно вычисленные семантические варианты отображения плашки.
 */
@Immutable
data class BadgeTextVariants(
    val full: String,
    val clean: String,
    val compact: String,
    val micro: String
)

/**
 * Вычисленный стиль и геометрия плашки для конкретного размера карточки.
 */
@Immutable
data class BadgeStyle(
    val visible: Boolean,
    val text: String,
    val fontSize: TextUnit,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val outerPadding: Dp,
    val cornerRadius: Dp,
    val maxBadgeWidth: Dp? = null
) {
    companion object {
        val HIDDEN = BadgeStyle(
            visible = false,
            text = "",
            fontSize = 9.sp,
            horizontalPadding = 0.dp,
            verticalPadding = 0.dp,
            outerPadding = 0.dp,
            cornerRadius = 0.dp,
            maxBadgeWidth = null
        )
    }
}

/**
 * Высокопроизводительный движок адаптации и форматирования плашек на постерах.
 * 
 * Особенности:
 * 1. Нулевой оверхед на CPU: парсинг и форматирование кэшируются в L1 LRU-кэше
 *    со статически прекомпилированными регулярными выражениями.
 * 2. Интеллектуальное синтаксическое сжатие: "3 сезон 16 серия (LostFilm)" при нехватке
 *    места автоматически трансформируется в "3 сезон 16 серия" -> "3с 16с".
 * 3. Железная защита от перекрытия постера: плашка никогда не переносится на несколько строк,
 *    имеет строгий maxLines = 1, Ellipsis и ограничение по ширине относительно постера.
 * 4. Защита от захламления на ультра-мелких сетках (например, 8-10 колонок).
 */
object PosterBadgeEngine {

    private val BRACKETS_REGEX = Regex("""\s*\([^)]*\)""")
    private val SEASON_EPISODE_REGEX = Regex("""(?i)(\d+)\s*сез[а-я\.]*\s*,?\s*(\d+)\s*сер[а-я\.]*""")
    private val SEASON_ONLY_REGEX = Regex("""(?i)(\d+)\s*сез[а-я\.]*""")
    private val EPISODE_ONLY_REGEX = Regex("""(?i)(\d+)\s*сер[а-я\.]*""")
    private val RATING_NUMBER_REGEX = Regex("""\b(\d+[\.,]\d+)\b""")

    // Потокобезопасный L1 Fast Cache разобранных строк (до 256 уникальных надписей)
    private val variantsCache = LruCache<String, BadgeTextVariants>(256)

    /**
     * Получение вариантов текста за O(1) из кэша.
     */
    fun getVariants(raw: String): BadgeTextVariants {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return BadgeTextVariants("", "", "", "")
        }

        synchronized(variantsCache) {
            val cached = variantsCache.get(trimmed)
            if (cached != null) return cached
        }

        val computed = parseBadgeVariants(trimmed)
        synchronized(variantsCache) {
            variantsCache.put(trimmed, computed)
        }
        return computed
    }

    private fun parseBadgeVariants(raw: String): BadgeTextVariants {
        val clean = BRACKETS_REGEX.replace(raw, "").trim()

        val seMatch = SEASON_EPISODE_REGEX.find(clean.ifEmpty { raw })
        if (seMatch != null) {
            val s = seMatch.groupValues[1]
            val e = seMatch.groupValues[2]
            return BadgeTextVariants(
                full = raw,
                clean = if (clean.isNotEmpty()) clean else raw,
                compact = "${s}с ${e}с",
                micro = "${s}с·${e}"
            )
        }

        val sMatch = SEASON_ONLY_REGEX.find(clean.ifEmpty { raw })
        if (sMatch != null) {
            val s = sMatch.groupValues[1]
            return BadgeTextVariants(
                full = raw,
                clean = if (clean.isNotEmpty()) clean else raw,
                compact = "${s}с",
                micro = "${s}с"
            )
        }

        val eMatch = EPISODE_ONLY_REGEX.find(clean.ifEmpty { raw })
        if (eMatch != null) {
            val e = eMatch.groupValues[1]
            return BadgeTextVariants(
                full = raw,
                clean = if (clean.isNotEmpty()) clean else raw,
                compact = "${e}с",
                micro = "${e}с"
            )
        }

        // Числовой рейтинг фильма
        val ratingMatch = RATING_NUMBER_REGEX.find(raw)
        if (ratingMatch != null) {
            val score = ratingMatch.groupValues[1].replace(',', '.')
            return BadgeTextVariants(
                full = raw,
                clean = score,
                compact = score,
                micro = score
            )
        }

        val shortClean = clean.take(12)
        return BadgeTextVariants(
            full = raw,
            clean = if (clean.isNotEmpty()) clean else raw,
            compact = shortClean,
            micro = shortClean.take(6)
        )
    }

    /**
     * Высокопроизводительный O(1) расчет стиля плашки под конкретный размер карточки.
     */
    fun resolveStyle(
        rawText: String,
        isSeries: Boolean,
        seriesBadgeMode: SeriesBadgeMode = RezkaService.seriesBadgeMode.value,
        cardWidth: Dp = Dp.Unspecified,
        cardHeight: Dp = Dp.Unspecified,
        columnsCount: Int = 2
    ): BadgeStyle {
        if (rawText.isBlank() || !isSeries || seriesBadgeMode == SeriesBadgeMode.DISABLED) {
            return BadgeStyle.HIDDEN
        }

        val variants = getVariants(rawText)

        // Оценка эффективной ширины и высоты постера в dp
        val effWidthDp = when {
            cardWidth != Dp.Unspecified -> cardWidth.value
            cardHeight != Dp.Unspecified -> cardHeight.value * 0.68f
            else -> (380f / columnsCount.coerceAtLeast(1))
        }

        val effHeightDp = when {
            cardHeight != Dp.Unspecified -> cardHeight.value
            else -> effWidthDp / 0.68f
        }

        // Если постер экстремально мал — в адаптивном режиме скрываем, чтобы не закрывать весь постер
        if (seriesBadgeMode == SeriesBadgeMode.ADAPTIVE && isSeries) {
            if (effWidthDp < 46f || effHeightDp < 64f) {
                return BadgeStyle.HIDDEN
            }
        }

        // 1. Ультра-плотный режим (колонки 7+, либо ширина < 72dp, либо высота < 105dp)
        if (effWidthDp < 72f || columnsCount >= 7 || effHeightDp < 105f) {
            val text = when (seriesBadgeMode) {
                SeriesBadgeMode.ORIGINAL -> variants.clean.ifEmpty { variants.full }
                SeriesBadgeMode.DISABLED -> return BadgeStyle.HIDDEN
                else -> variants.compact
            }
            return BadgeStyle(
                visible = true,
                text = text,
                fontSize = 7.sp,
                horizontalPadding = 3.dp,
                verticalPadding = 1.dp,
                outerPadding = 2.dp,
                cornerRadius = 3.dp,
                maxBadgeWidth = (effWidthDp * 0.85f).dp
            )
        }

        // 2. Компактный режим (колонки 4-6, либо ширина 72..115dp, либо высота 105..155dp)
        if (effWidthDp < 115f || columnsCount in 4..6 || effHeightDp < 155f) {
            val text = when (seriesBadgeMode) {
                SeriesBadgeMode.ORIGINAL -> variants.full
                SeriesBadgeMode.COMPACT -> variants.compact
                SeriesBadgeMode.DISABLED -> return BadgeStyle.HIDDEN
                SeriesBadgeMode.ADAPTIVE -> {
                    // Если строка длинная (более 10 символов), сжимаем до компактной, чтобы не перекрывать постер
                    if (isSeries && variants.clean.length > 10) {
                        variants.compact
                    } else {
                        variants.clean
                    }
                }
            }
            return BadgeStyle(
                visible = true,
                text = text,
                fontSize = 8.5.sp,
                horizontalPadding = 4.5.dp,
                verticalPadding = 2.dp,
                outerPadding = 4.dp,
                cornerRadius = 4.dp,
                maxBadgeWidth = (effWidthDp * 0.86f).dp
            )
        }

        // 3. Стандартный и просторный режим (1-3 колонки, ширина >= 115dp)
        val text = when (seriesBadgeMode) {
            SeriesBadgeMode.ORIGINAL -> variants.full
            SeriesBadgeMode.COMPACT -> variants.compact
            SeriesBadgeMode.DISABLED -> return BadgeStyle.HIDDEN
            SeriesBadgeMode.ADAPTIVE -> {
                if (isSeries) variants.clean else variants.clean
            }
        }

        return BadgeStyle(
            visible = true,
            text = text,
            fontSize = 10.5.sp,
            horizontalPadding = 6.dp,
            verticalPadding = 3.dp,
            outerPadding = 6.dp,
            cornerRadius = 6.dp,
            maxBadgeWidth = (effWidthDp * 0.88f).dp
        )
    }
}

/**
 * Адаптивная плашка на постере, гарантирующая идеальное масштабирование
 * под размер карточки и предотвращающая перекрытие постера.
 */
@Composable
fun AdaptivePosterBadge(
    rawText: String,
    isSeries: Boolean,
    modifier: Modifier = Modifier,
    columnsCount: Int = 2,
    cardHeight: Dp = Dp.Unspecified,
    cardWidth: Dp = Dp.Unspecified,
    seriesBadgeMode: SeriesBadgeMode = RezkaService.seriesBadgeMode.value,
    alignment: Alignment = Alignment.TopEnd
) {
    val style = PosterBadgeEngine.resolveStyle(
        rawText = rawText,
        isSeries = isSeries,
        seriesBadgeMode = seriesBadgeMode,
        cardWidth = cardWidth,
        cardHeight = cardHeight,
        columnsCount = columnsCount
    )

    if (!style.visible || style.text.isEmpty()) return

    Box(
        modifier = modifier
            .padding(style.outerPadding)
            .then(if (style.maxBadgeWidth != null) Modifier.widthIn(max = style.maxBadgeWidth) else Modifier)
            .background(CinemaPrimary, RoundedCornerShape(style.cornerRadius))
            .padding(horizontal = style.horizontalPadding, vertical = style.verticalPadding)
    ) {
        Text(
            text = style.text,
            color = CinemaTextWhite,
            fontSize = style.fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
