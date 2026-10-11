package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.example.data.StreamUrl
import com.example.data.SubtitleTrack
import com.example.data.Translator
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import com.example.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Тип активного контекстного меню плеера.
 */
enum class PlayerMenuType {
    NONE,
    QUALITY,
    SPEED,
    TRANSLATOR,
    SUBTITLES
}

/**
 * Высокопроизводительный провайдер позиции для отображения всплывающего меню
 * строго над кнопкой-якорем (anchor button).
 * Предотвращает вылет за пределы экрана и обрезания на любых диагоналях (смартфоны, планшеты, ТВ).
 */
class PlayerMenuPositionProvider(private val density: Float) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val gapPx = (6 * density).roundToInt()
        val marginPx = (10 * density).roundToInt()

        // Размещаем меню строго НАД верхним краем кнопки-якоря
        var y = anchorBounds.top - popupContentSize.height - gapPx

        // Если меню слишком высокое и упирается в верхний край экрана - прижимаем к верхнему отступу
        if (y < marginPx) {
            y = marginPx
        }

        // Выравниваем левый край меню по левому краю кнопки, предотвращая вылет за правый край
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = anchorBounds.left.coerceIn(marginPx, maxX)

        return IntOffset(x, y)
    }
}

/**
 * Базовый контейнер всплывающего меню в цветах кнопок плеера (тёмный полупрозрачный фон,
 * тонкая рамка CinemaBorder, скругление углов и тень).
 */
@Composable
fun PlayerAnchoredPopup(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current.density
    val positionProvider = remember(density) { PlayerMenuPositionProvider(density) }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            color = Color(0xFF141418).copy(alpha = 0.94f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CinemaBorder.copy(alpha = 0.75f)),
            shadowElevation = 10.dp,
            modifier = modifier
                .widthIn(min = 150.dp, max = 270.dp)
                .heightIn(max = 220.dp)
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * Строка элемента меню с подсветкой фона акцентным цветом CinemaPrimary на текущей настройке.
 * Текст и галочка НЕ акцентные (белый цвет), что сохраняет строгий и лаконичный стиль.
 */
@Composable
fun PlayerMenuItemRow(
    title: String,
    isSelected: Boolean,
    isRemoteFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingBadge: (@Composable () -> Unit)? = null
) {
    val backgroundColor = when {
        isRemoteFocused && isSelected -> CinemaPrimary.copy(alpha = 0.38f)
        isRemoteFocused -> CinemaPrimary.copy(alpha = 0.22f)
        isSelected -> CinemaPrimary.copy(alpha = 0.25f)
        else -> Color.Transparent
    }

    val borderStroke = when {
        isRemoteFocused -> BorderStroke(2.dp, CinemaPrimary)
        isSelected -> BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.55f))
        else -> null
    }

    // Цвет текста всегда белый / светлый (не акцентный)
    val textColor = Color.White
    val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp),
        border = borderStroke,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingContent != null) {
                    leadingContent()
                    Spacer(modifier = Modifier.width(7.dp))
                }
                Column {
                    Text(
                        text = title,
                        color = textColor,
                        fontWeight = fontWeight,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        Text(
                            text = subtitle,
                            color = CinemaTextGray,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
                if (trailingBadge != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    trailingBadge()
                }
            }

            // Галочка текущего выбора (белая, не акцентная)
            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Меню выбора качества видео над кнопкой качества (без лишнего заголовка, с вертикальной прокруткой).
 */
@Composable
fun QualityMenuPopup(
    streams: List<StreamUrl>,
    selectedIndex: Int,
    remoteFocusedIndex: Int,
    onSelect: (Int, StreamUrl) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current.density

    LaunchedEffect(remoteFocusedIndex) {
        if (remoteFocusedIndex >= 0) {
            val itemHeightPx = (38 * density).roundToInt()
            val targetScroll = (remoteFocusedIndex * itemHeightPx - (40 * density).roundToInt()).coerceAtLeast(0)
            scrollState.animateScrollTo(targetScroll)
        }
    }

    PlayerAnchoredPopup(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            streams.forEachIndexed { index, stream ->
                val isSelected = index == selectedIndex
                val isRemoteFocused = index == remoteFocusedIndex
                PlayerMenuItemRow(
                    title = stream.quality,
                    isSelected = isSelected,
                    isRemoteFocused = isRemoteFocused,
                    onClick = {
                        HapticEngine.get().perform(HapticType.SELECTION)
                        onSelect(index, stream)
                    }
                )
            }
        }
    }
}

/**
 * Меню выбора скорости воспроизведения над кнопкой скорости (без лишнего заголовка, с вертикальной прокруткой).
 */
@Composable
fun SpeedMenuPopup(
    currentSpeed: Float,
    remoteFocusedIndex: Int,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val availableSpeeds = remember { listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current.density

    LaunchedEffect(remoteFocusedIndex) {
        if (remoteFocusedIndex >= 0) {
            val itemHeightPx = (38 * density).roundToInt()
            val targetScroll = (remoteFocusedIndex * itemHeightPx - (40 * density).roundToInt()).coerceAtLeast(0)
            scrollState.animateScrollTo(targetScroll)
        }
    }

    PlayerAnchoredPopup(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            availableSpeeds.forEachIndexed { index, speed ->
                val isSelected = abs(speed - currentSpeed) < 0.01f
                val isRemoteFocused = index == remoteFocusedIndex
                val label = when (speed) {
                    1.0f -> "1.0x (Обычная)"
                    1.5f -> "1.5x (Быстрая)"
                    2.0f -> "2.0x (Двойная)"
                    else -> "${speed}x"
                }

                PlayerMenuItemRow(
                    title = label,
                    isSelected = isSelected,
                    isRemoteFocused = isRemoteFocused,
                    onClick = {
                        HapticEngine.get().perform(HapticType.SELECTION)
                        onSelect(speed)
                    }
                )
            }
        }
    }
}

/**
 * Меню выбора озвучки над кнопкой озвучки (без лишнего заголовка, с вертикальной прокруткой).
 */
@Composable
fun TranslatorMenuPopup(
    translators: List<Translator>,
    currentTranslatorId: String?,
    remoteFocusedIndex: Int,
    onSelect: (Translator) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current.density

    LaunchedEffect(remoteFocusedIndex) {
        if (remoteFocusedIndex >= 0) {
            val itemHeightPx = (38 * density).roundToInt()
            val targetScroll = (remoteFocusedIndex * itemHeightPx - (40 * density).roundToInt()).coerceAtLeast(0)
            scrollState.animateScrollTo(targetScroll)
        }
    }

    PlayerAnchoredPopup(
        onDismiss = onDismiss,
        modifier = Modifier.widthIn(min = 170.dp, max = 290.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            translators.forEachIndexed { index, translator ->
                val isSelected = translator.id == currentTranslatorId
                val isRemoteFocused = index == remoteFocusedIndex

                PlayerMenuItemRow(
                    title = translator.name,
                    isSelected = isSelected,
                    isRemoteFocused = isRemoteFocused,
                    onClick = {
                        HapticEngine.get().perform(HapticType.SELECTION)
                        onSelect(translator)
                    },
                    leadingContent = if (translator.flagUrl.isNotEmpty()) {
                        {
                            AsyncImage(
                                model = translator.flagUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .height(13.dp)
                                    .widthIn(max = 20.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }
                    } else null,
                    trailingBadge = if (translator.isPremium && translator.premiumUrl.isNotEmpty()) {
                        {
                            AsyncImage(
                                model = translator.premiumUrl,
                                contentDescription = "Премиум",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .height(13.dp)
                                    .widthIn(max = 20.dp)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/**
 * Меню выбора субтитров над кнопкой субтитров с быстрым переключением размера шрифта (с вертикальной прокруткой).
 */
@Composable
fun SubtitlesMenuPopup(
    isSubtitlesEnabled: Boolean,
    selectedSubtitleTrack: SubtitleTrack?,
    availableSubtitles: List<SubtitleTrack>,
    subtitleTextScale: Float,
    remoteFocusedIndex: Int,
    onSelectOff: () -> Unit,
    onSelectTrack: (SubtitleTrack) -> Unit,
    onSelectScale: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current.density

    LaunchedEffect(remoteFocusedIndex) {
        if (remoteFocusedIndex >= 0) {
            val itemHeightPx = (38 * density).roundToInt()
            val targetScroll = (remoteFocusedIndex * itemHeightPx - (40 * density).roundToInt()).coerceAtLeast(0)
            scrollState.animateScrollTo(targetScroll)
        }
    }

    PlayerAnchoredPopup(
        onDismiss = onDismiss,
        modifier = Modifier.widthIn(min = 180.dp, max = 280.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Опция: Выключены
            val isOff = !isSubtitlesEnabled || selectedSubtitleTrack == null
            val isOffRemoteFocused = remoteFocusedIndex == 0
            PlayerMenuItemRow(
                title = "Выключены",
                isSelected = isOff,
                isRemoteFocused = isOffRemoteFocused,
                onClick = {
                    HapticEngine.get().perform(HapticType.SELECTION)
                    onSelectOff()
                }
            )

            // Список дорожек субтитров
            if (availableSubtitles.isNotEmpty()) {
                HorizontalDivider(
                    color = CinemaBorder.copy(alpha = 0.35f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )

                availableSubtitles.forEachIndexed { index, track ->
                    val isTrackSelected = isSubtitlesEnabled && selectedSubtitleTrack?.url == track.url
                    val isTrackRemoteFocused = remoteFocusedIndex == (index + 1)

                    PlayerMenuItemRow(
                        title = track.title,
                        subtitle = if (track.language.isNotEmpty() && track.language != "und") track.language.uppercase() else null,
                        isSelected = isTrackSelected,
                        isRemoteFocused = isTrackRemoteFocused,
                        onClick = {
                            HapticEngine.get().perform(HapticType.SELECTION)
                            onSelectTrack(track)
                        }
                    )
                }
            }

            // Блок выбора размера шрифта
            HorizontalDivider(
                color = CinemaBorder.copy(alpha = 0.35f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val sizeOptions = listOf(
                    "Мелкий" to 0.042f,
                    "Обычный" to 0.053f,
                    "Крупный" to 0.068f,
                    "Макс" to 0.082f
                )
                sizeOptions.forEach { (label, scale) ->
                    val isChosen = abs(subtitleTextScale - scale) < 0.005f
                    Surface(
                        color = if (isChosen) CinemaPrimary.copy(alpha = 0.35f) else CinemaSurface,
                        border = if (isChosen) BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.6f)) else null,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                onSelectScale(scale)
                            }
                    ) {
                        Text(
                            text = label,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Normal,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
