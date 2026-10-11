package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.RezkaDetail
import com.example.data.RezkaType
import com.example.data.Season
import com.example.data.Translator
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import com.example.ui.theme.*
import com.example.ui.tv.TvDetector
import com.example.ui.tv.dpadScrollable
import com.example.ui.tv.requestFocusSafe
import com.example.ui.tv.tvFocusableItem

/**
 * Высокопроизводительное, горизонтальное адаптивное окно загрузки для телевизоров и смартфонов.
 *
 * Архитектурные свойства:
 * 1. На ТВ (и в ландшафтном режиме): эргономичная 2-колоночная компоновка без вертикального раздутия.
 * 2. Полный скроллинг: контейнер диалога снабжен скроллингом и D-Pad прокруткой dpadScrollable.
 * 3. Горизонтальный ряд выбора качества вместо громоздких дропдаунов.
 * 4. Четкие фокусные границы на всех элементах управления с пульта.
 */
@Composable
fun MediaDownloadDialog(
    detail: RezkaDetail,
    initialTranslator: Translator?,
    initialSeasonId: Int?,
    initialEpisodeId: String?,
    effectiveSeasons: List<Season>,
    defaultQuality: String,
    onDismiss: () -> Unit,
    onDownload: (translator: Translator, seasonId: Int, episodeId: String, quality: String, isOfflineLibrary: Boolean) -> Unit
) {
    val context = LocalContext.current
    val isTvDevice = remember { TvDetector.isRunningOnTv(context) }
    val isSeries = detail.type == RezkaType.SERIES

    var selectedTranslator by remember {
        mutableStateOf(initialTranslator ?: detail.translators.firstOrNull() ?: Translator("0", "Основной"))
    }

    val availableSeasons = effectiveSeasons.ifEmpty { detail.seasons }

    var selectedSeasonId by remember {
        mutableIntStateOf(initialSeasonId ?: availableSeasons.firstOrNull()?.id ?: 1)
    }

    val currentSeason = availableSeasons.find { it.id == selectedSeasonId } ?: availableSeasons.firstOrNull()

    var selectedEpisodeId by remember {
        mutableStateOf(
            initialEpisodeId ?: currentSeason?.episodes?.firstOrNull()?.id ?: "1"
        )
    }

    val availableEpisodes = currentSeason?.episodes ?: emptyList()
    val currentEpisode = availableEpisodes.find { it.id == selectedEpisodeId } ?: availableEpisodes.firstOrNull()

    val qualityOptions = remember { listOf("1080p", "720p", "480p", "360p") }
    var selectedQuality by remember {
        mutableStateOf(if (defaultQuality in qualityOptions) defaultQuality else "1080p")
    }

    var translatorDropdownExpanded by remember { mutableStateOf(false) }
    var seasonDropdownExpanded by remember { mutableStateOf(false) }
    var episodeDropdownExpanded by remember { mutableStateOf(false) }

    val initialDialogRequester = remember { FocusRequester() }
    val dialogScrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        initialDialogRequester.requestFocusSafe()
    }

    val performDownload: (Boolean) -> Unit = { isOfflineLib ->
        HapticEngine.get().perform(HapticType.CONFIRM)
        onDownload(
            selectedTranslator,
            if (isSeries) selectedSeasonId else 0,
            if (isSeries) selectedEpisodeId else "",
            selectedQuality,
            isOfflineLib
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val isWideLayout = maxWidth >= 600.dp || isTvDevice
            val dialogWidth = if (isWideLayout) minOf(maxWidth * 0.88f, 820.dp) else maxWidth * 0.95f
            val dialogMaxHeight = if (isWideLayout) minOf(maxHeight * 0.92f, 520.dp) else maxHeight * 0.92f

            Surface(
                color = CinemaDark,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.45f)),
                modifier = Modifier
                    .width(dialogWidth)
                    .heightIn(max = dialogMaxHeight)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(dialogScrollState)
                        .dpadScrollable(dialogScrollState)
                ) {
                    // 1. Верхняя плашка: Заголовок и кнопка закрытия
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = CinemaPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = if (isSeries) "Загрузка сериала" else "Загрузка фильма",
                                    color = CinemaTextWhite,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = detail.title,
                                    color = CinemaTextGray,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Surface(
                            color = CinemaCard,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, CinemaBorder),
                            modifier = Modifier
                                .size(32.dp)
                                .tvFocusableItem(
                                    onClick = onDismiss,
                                    shape = RoundedCornerShape(8.dp),
                                    scaleFactor = 1.05f
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Закрыть",
                                    tint = CinemaTextWhite,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isWideLayout) {
                        // 2. Горизонтальная компоновка для ТВ
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // ЛЕВАЯ КОЛОНКА: Выбор параметров (Сезон/Серия, Озвучка, Качество)
                            Column(
                                modifier = Modifier.weight(1.15f),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Сезон и Серия в один ряд
                                if (isSeries && availableSeasons.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Сезон
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Сезон",
                                                color = CinemaTextGray,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Box(modifier = Modifier.fillMaxWidth()) {
                                                Surface(
                                                    color = CinemaCard,
                                                    shape = RoundedCornerShape(8.dp),
                                                    border = BorderStroke(1.dp, CinemaBorder),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(38.dp)
                                                        .tvFocusableItem(
                                                            onClick = { seasonDropdownExpanded = true },
                                                            shape = RoundedCornerShape(8.dp),
                                                            focusRequester = initialDialogRequester
                                                        )
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(horizontal = 10.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = currentSeason?.name ?: "Сезон $selectedSeasonId",
                                                            color = CinemaTextWhite,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Icon(
                                                            imageVector = if (seasonDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                            contentDescription = null,
                                                            tint = CinemaPrimary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                                DropdownMenu(
                                                    expanded = seasonDropdownExpanded,
                                                    onDismissRequest = { seasonDropdownExpanded = false },
                                                    modifier = Modifier
                                                        .background(CinemaDark)
                                                        .border(1.dp, CinemaPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                                        .widthIn(min = 180.dp, max = 260.dp)
                                                        .heightIn(max = 240.dp)
                                                        .padding(4.dp)
                                                ) {
                                                    availableSeasons.forEach { s ->
                                                        val isSelected = s.id == selectedSeasonId
                                                        Surface(
                                                            color = if (isSelected) CinemaPrimary.copy(alpha = 0.2f) else Color.Transparent,
                                                            shape = RoundedCornerShape(6.dp),
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(34.dp)
                                                                .tvFocusableItem(
                                                                    onClick = {
                                                                        selectedSeasonId = s.id
                                                                        selectedEpisodeId = s.episodes.firstOrNull()?.id ?: "1"
                                                                        seasonDropdownExpanded = false
                                                                    },
                                                                    shape = RoundedCornerShape(6.dp)
                                                                )
                                                        ) {
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxSize()
                                                                    .padding(horizontal = 10.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    text = s.name,
                                                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Серия
                                        if (availableEpisodes.isNotEmpty()) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Серия",
                                                    color = CinemaTextGray,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Box(modifier = Modifier.fillMaxWidth()) {
                                                    Surface(
                                                        color = CinemaCard,
                                                        shape = RoundedCornerShape(8.dp),
                                                        border = BorderStroke(1.dp, CinemaBorder),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(38.dp)
                                                            .tvFocusableItem(
                                                                onClick = { episodeDropdownExpanded = true },
                                                                shape = RoundedCornerShape(8.dp)
                                                            )
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .padding(horizontal = 10.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = currentEpisode?.name ?: "Серия $selectedEpisodeId",
                                                                color = CinemaTextWhite,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Icon(
                                                                imageVector = if (episodeDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                                contentDescription = null,
                                                                tint = CinemaPrimary,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }
                                                    DropdownMenu(
                                                        expanded = episodeDropdownExpanded,
                                                        onDismissRequest = { episodeDropdownExpanded = false },
                                                        modifier = Modifier
                                                            .background(CinemaDark)
                                                            .border(1.dp, CinemaPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                                            .widthIn(min = 180.dp, max = 260.dp)
                                                            .heightIn(max = 240.dp)
                                                            .padding(4.dp)
                                                    ) {
                                                        availableEpisodes.forEach { ep ->
                                                            val isSelected = ep.id == selectedEpisodeId
                                                            Surface(
                                                                color = if (isSelected) CinemaPrimary.copy(alpha = 0.2f) else Color.Transparent,
                                                                shape = RoundedCornerShape(6.dp),
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .height(34.dp)
                                                                    .tvFocusableItem(
                                                                        onClick = {
                                                                            selectedEpisodeId = ep.id
                                                                            episodeDropdownExpanded = false
                                                                        },
                                                                        shape = RoundedCornerShape(6.dp)
                                                                    )
                                                            ) {
                                                                Row(
                                                                    modifier = Modifier
                                                                        .fillMaxSize()
                                                                        .padding(horizontal = 10.dp),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text(
                                                                        text = ep.name,
                                                                        color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                                        fontSize = 12.sp,
                                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Озвучка
                                if (detail.translators.isNotEmpty()) {
                                    Column {
                                        Text(
                                            text = "Озвучка / Перевод",
                                            color = CinemaTextGray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Box(modifier = Modifier.fillMaxWidth()) {
                                            Surface(
                                                color = CinemaCard,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, CinemaBorder),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(38.dp)
                                                    .tvFocusableItem(
                                                        onClick = { translatorDropdownExpanded = true },
                                                        shape = RoundedCornerShape(8.dp),
                                                        focusRequester = if (!isSeries || availableSeasons.isEmpty()) initialDialogRequester else null
                                                    )
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(horizontal = 10.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    ) {
                                                        if (selectedTranslator.flagUrl.isNotEmpty()) {
                                                            AsyncImage(
                                                                model = selectedTranslator.flagUrl,
                                                                contentDescription = null,
                                                                contentScale = ContentScale.Fit,
                                                                modifier = Modifier
                                                                    .padding(end = 6.dp)
                                                                    .height(12.dp)
                                                                    .widthIn(max = 20.dp)
                                                                    .clip(RoundedCornerShape(2.dp))
                                                            )
                                                        }
                                                        Text(
                                                            text = selectedTranslator.name,
                                                            color = CinemaTextWhite,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Icon(
                                                        imageVector = if (translatorDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                        contentDescription = null,
                                                        tint = CinemaPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = translatorDropdownExpanded,
                                                onDismissRequest = { translatorDropdownExpanded = false },
                                                modifier = Modifier
                                                    .background(CinemaDark)
                                                    .border(1.dp, CinemaPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                                    .widthIn(min = 200.dp, max = 320.dp)
                                                    .heightIn(max = 240.dp)
                                                    .padding(4.dp)
                                            ) {
                                                detail.translators.forEach { trans ->
                                                    val isSelected = trans.id == selectedTranslator.id
                                                    Surface(
                                                        color = if (isSelected) CinemaPrimary.copy(alpha = 0.2f) else Color.Transparent,
                                                        shape = RoundedCornerShape(6.dp),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(34.dp)
                                                            .tvFocusableItem(
                                                                onClick = {
                                                                    selectedTranslator = trans
                                                                    translatorDropdownExpanded = false
                                                                },
                                                                shape = RoundedCornerShape(6.dp)
                                                            )
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .padding(horizontal = 10.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            if (trans.flagUrl.isNotEmpty()) {
                                                                AsyncImage(
                                                                    model = trans.flagUrl,
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Fit,
                                                                    modifier = Modifier
                                                                        .padding(end = 6.dp)
                                                                        .height(12.dp)
                                                                        .widthIn(max = 20.dp)
                                                                        .clip(RoundedCornerShape(2.dp))
                                                                )
                                                            }
                                                            Text(
                                                                text = trans.name,
                                                                color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                                fontSize = 12.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Горизонтальный ряд выбора качества
                                Column {
                                    Text(
                                        text = "Качество видео",
                                        color = CinemaTextGray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        qualityOptions.forEach { q ->
                                            val isSelected = q == selectedQuality
                                            Surface(
                                                color = if (isSelected) CinemaPrimary.copy(alpha = 0.25f) else CinemaCard,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(
                                                    if (isSelected) 1.5.dp else 1.dp,
                                                    if (isSelected) CinemaPrimary else CinemaBorder
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(36.dp)
                                                    .tvFocusableItem(
                                                        onClick = { selectedQuality = q },
                                                        shape = RoundedCornerShape(8.dp),
                                                        scaleFactor = 1.05f
                                                    )
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize()
                                                ) {
                                                    Text(
                                                        text = q,
                                                        color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // ПРАВАЯ КОЛОНКА: Сводка и Кнопки действий
                            Column(
                                modifier = Modifier.weight(0.85f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Карточка сводки
                                Surface(
                                    color = CinemaCard,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, CinemaBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (detail.imageUrl.isNotEmpty()) {
                                            AsyncImage(
                                                model = detail.imageUrl,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .width(38.dp)
                                                    .height(54.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            )
                                        }
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = if (isSeries) "${currentSeason?.name.orEmpty()} • ${currentEpisode?.name.orEmpty()}" else "Полный фильм",
                                                color = CinemaTextWhite,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = selectedTranslator.name,
                                                color = CinemaTextGray,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "Качество: $selectedQuality",
                                                color = CinemaPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                // Кнопка 1: "В оффлайн библиотеку"
                                Surface(
                                    color = CinemaCard,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.7f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .tvFocusableItem(
                                            onClick = { performDownload(true) },
                                            shape = RoundedCornerShape(8.dp),
                                            scaleFactor = 1.02f
                                        )
                                        .testTag("download_offline_library_button")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudOff,
                                            contentDescription = null,
                                            tint = CinemaPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "В оффлайн библиотеку",
                                            color = CinemaTextWhite,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                // Кнопка 2: "Обычное скачивание"
                                Surface(
                                    color = CinemaCard,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .tvFocusableItem(
                                            onClick = { performDownload(false) },
                                            shape = RoundedCornerShape(8.dp),
                                            scaleFactor = 1.02f
                                        )
                                        .testTag("download_regular_button")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = null,
                                            tint = CinemaPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Обычное скачивание",
                                            color = CinemaTextWhite,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Вертикальная компоновка (для узких экранов телефонов), но с полным скроллом!
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (isSeries && availableSeasons.isNotEmpty()) {
                                // Сезон
                                Text(text = "Сезон: ${currentSeason?.name ?: ""}", color = CinemaTextWhite, fontSize = 13.sp)
                            }
                            Text(text = "Озвучка: ${selectedTranslator.name}", color = CinemaTextWhite, fontSize = 13.sp)
                            
                            // Качество
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                qualityOptions.forEach { q ->
                                    val isSelected = q == selectedQuality
                                    Surface(
                                        color = if (isSelected) CinemaPrimary.copy(alpha = 0.25f) else CinemaCard,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, if (isSelected) CinemaPrimary else CinemaBorder),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clickable { selectedQuality = q }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(text = q, color = if (isSelected) CinemaPrimary else CinemaTextWhite, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Surface(
                                color = CinemaCard,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, CinemaPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .clickable { performDownload(true) }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(imageVector = Icons.Default.CloudOff, contentDescription = null, tint = CinemaPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "В оффлайн библиотеку", color = CinemaTextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Surface(
                                color = CinemaCard,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, CinemaBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .clickable { performDownload(false) }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(imageVector = Icons.Default.Download, contentDescription = null, tint = CinemaPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "Обычное скачивание", color = CinemaTextWhite, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDownloadDialog(
    detail: RezkaDetail,
    initialTranslator: Translator?,
    initialSeasonId: Int?,
    initialEpisodeId: String?,
    effectiveSeasons: List<Season>,
    defaultQuality: String,
    onDismiss: () -> Unit,
    onDownload: (translator: Translator, seasonId: Int, episodeId: String, quality: String, isOfflineLibrary: Boolean) -> Unit
) {
    MediaDownloadDialog(
        detail = detail,
        initialTranslator = initialTranslator,
        initialSeasonId = initialSeasonId,
        initialEpisodeId = initialEpisodeId,
        effectiveSeasons = effectiveSeasons,
        defaultQuality = defaultQuality,
        onDismiss = onDismiss,
        onDownload = onDownload
    )
}
