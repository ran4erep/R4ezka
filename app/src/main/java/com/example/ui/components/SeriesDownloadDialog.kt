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
import com.example.data.RezkaDecryptor
import com.example.data.RezkaDetail
import com.example.data.RezkaType
import com.example.data.Season
import com.example.data.StreamUrl
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
    initialStreams: List<StreamUrl>? = null,
    fetchStreams: (suspend (translator: Translator, seasonId: Int, episodeId: String) -> List<StreamUrl>)? = null,
    fetchEpisodes: (suspend (translator: Translator) -> List<Season>)? = null,
    onDismiss: () -> Unit,
    onDownload: (translator: Translator, seasonId: Int, episodeId: String, quality: String, isOfflineLibrary: Boolean) -> Unit
) {
    val context = LocalContext.current
    val isTvDevice = remember { TvDetector.isRunningOnTv(context) }
    val isSeries = detail.hasEpisodes

    var selectedTranslator by remember {
        mutableStateOf(initialTranslator ?: detail.translators.firstOrNull() ?: Translator("0", "Основной"))
    }

    var dynamicSeasons by remember {
        mutableStateOf(effectiveSeasons.ifEmpty { detail.seasons })
    }

    val availableSeasons = dynamicSeasons.ifEmpty { detail.seasons }

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

    // Динамическое обновление серий при смене озвучки (если передана функция получения серий)
    LaunchedEffect(selectedTranslator.id) {
        if (fetchEpisodes != null && isSeries) {
            try {
                val eps = fetchEpisodes(selectedTranslator)
                if (eps.isNotEmpty()) {
                    dynamicSeasons = eps
                    if (eps.none { it.id == selectedSeasonId }) {
                        selectedSeasonId = eps.first().id
                        selectedEpisodeId = eps.first().episodes.firstOrNull()?.id ?: "1"
                    } else {
                        val seasonObj = eps.find { it.id == selectedSeasonId }
                        if (seasonObj != null && seasonObj.episodes.none { it.id == selectedEpisodeId }) {
                            selectedEpisodeId = seasonObj.episodes.firstOrNull()?.id ?: "1"
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Локальный кэш потоков в памяти: "translatorId_seasonId_episodeId" -> List<StreamUrl>
    val streamsCache = remember { mutableMapOf<String, List<StreamUrl>>() }

    // Посев кэша переданными потоками активного видео
    val initialKey = remember(selectedTranslator.id, selectedSeasonId, selectedEpisodeId) {
        "${selectedTranslator.id}_${if (isSeries) selectedSeasonId else 0}_${if (isSeries) selectedEpisodeId else ""}"
    }
    LaunchedEffect(initialStreams) {
        if (initialStreams != null && initialStreams.isNotEmpty() && !streamsCache.containsKey(initialKey)) {
            val remote = initialStreams.filter { !it.url.startsWith("file://") && !it.directMp4Url.startsWith("file://") }
            if (remote.isNotEmpty()) {
                streamsCache[initialKey] = remote
            }
        }
    }

    // Список реально доступных качеств для текущего выбранного видео/серии
    var availableQualities by remember {
        val initialQualities = initialStreams?.filter {
            !it.url.startsWith("file://") && !it.directMp4Url.startsWith("file://")
        }?.map { it.quality }?.distinct()?.filter { it.isNotBlank() }
        mutableStateOf<List<String>>(initialQualities ?: emptyList())
    }
    var isLoadingQualities by remember { mutableStateOf(false) }

    // Асинхронное получение реальных качеств с CDN (с кэшированием в O(1) и отменой предыдущих корутин)
    LaunchedEffect(selectedTranslator.id, selectedSeasonId, selectedEpisodeId) {
        val sId = if (isSeries) selectedSeasonId else 0
        val epId = if (isSeries) selectedEpisodeId else ""
        val cacheKey = "${selectedTranslator.id}_${sId}_${epId}"

        val cached = streamsCache[cacheKey]
        if (cached != null && cached.isNotEmpty()) {
            val qualities = cached.map { it.quality }.distinct().filter { it.isNotBlank() }
            if (qualities.isNotEmpty()) {
                availableQualities = qualities
                isLoadingQualities = false
                return@LaunchedEffect
            }
        }

        if (fetchStreams != null) {
            isLoadingQualities = true
            try {
                val fetched = fetchStreams(selectedTranslator, sId, epId)
                val remote = fetched.filter { !it.url.startsWith("file://") && !it.directMp4Url.startsWith("file://") }
                if (remote.isNotEmpty()) {
                    streamsCache[cacheKey] = remote
                    val qualities = remote.map { it.quality }.distinct().filter { it.isNotBlank() }
                    if (qualities.isNotEmpty()) {
                        availableQualities = qualities
                    }
                }
            } catch (_: Exception) {
                // В случае сетевого сбоя оставляем ранее доступные качества
            } finally {
                isLoadingQualities = false
            }
        }
    }

    // Опции качества: строго реальные потоки, отсортированные по убыванию разрешения (например: 1080p -> 720p -> 480p -> 360p)
    val qualityOptions = remember(availableQualities, isLoadingQualities) {
        if (availableQualities.isNotEmpty()) {
            availableQualities.sortedByDescending { RezkaDecryptor.getQualityWeight(it) }
        } else if (!isLoadingQualities) {
            // Безопасный резервный список, если сеть полностью недоступна
            listOf("1080p", "720p", "480p", "360p")
        } else {
            emptyList()
        }
    }

    var selectedQuality by remember {
        mutableStateOf(
            if (availableQualities.isNotEmpty()) {
                val matching = availableQualities.firstOrNull { it.equals(defaultQuality, ignoreCase = true) }
                matching ?: availableQualities.first()
            } else {
                defaultQuality
            }
        )
    }

    // Автоматическая корректировка выбранного качества при изменении набора доступных
    LaunchedEffect(qualityOptions) {
        if (qualityOptions.isNotEmpty() && selectedQuality !in qualityOptions) {
            val matching = qualityOptions.firstOrNull { it.equals(defaultQuality, ignoreCase = true) }
            selectedQuality = matching ?: qualityOptions.first()
        }
    }

    var translatorDropdownExpanded by remember { mutableStateOf(false) }
    var seasonDropdownExpanded by remember { mutableStateOf(false) }
    var episodeDropdownExpanded by remember { mutableStateOf(false) }
    var mobileSeasonDropdownExpanded by remember { mutableStateOf(false) }
    var mobileEpisodeDropdownExpanded by remember { mutableStateOf(false) }
    var mobileTranslatorDropdownExpanded by remember { mutableStateOf(false) }

    val initialDialogRequester = remember { FocusRequester() }
    val dialogScrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        initialDialogRequester.requestFocusSafe()
    }

    val performDownload: (Boolean) -> Unit = { isOfflineLib ->
        if (!isLoadingQualities || qualityOptions.isNotEmpty()) {
            HapticEngine.get().perform(HapticType.CONFIRM)
            val finalQuality = if (selectedQuality in qualityOptions) {
                selectedQuality
            } else {
                qualityOptions.firstOrNull() ?: defaultQuality
            }
            onDownload(
                selectedTranslator,
                if (isSeries) selectedSeasonId else 0,
                if (isSeries) selectedEpisodeId else "",
                finalQuality,
                isOfflineLib
            )
            onDismiss()
        }
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
                                    text = if (detail.isMultiSeriesMovie || !isSeries) "Загрузка фильма" else "Загрузка сериала",
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
                                    if (!detail.isMultiSeriesMovie) {
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
                                    } else {
                                        // Многосерийный фильм: список сезонов скрываем, отображаем только серию/часть
                                        if (availableEpisodes.isNotEmpty()) {
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Text(
                                                    text = "Серия / Часть",
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
                                                        focusRequester = if (!isSeries || availableSeasons.isEmpty() || (detail.isMultiSeriesMovie && availableEpisodes.isEmpty())) initialDialogRequester else null
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
                                    if (isLoadingQualities && qualityOptions.isEmpty()) {
                                        Surface(
                                            color = CinemaCard,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, CinemaBorder),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(36.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    color = CinemaPrimary,
                                                    strokeWidth = 2.dp
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Определение доступного качества...",
                                                    color = CinemaTextGray,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    } else {
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
                                                text = if (isSeries) {
                                                    if (detail.isMultiSeriesMovie) {
                                                        currentEpisode?.name.orEmpty()
                                                    } else {
                                                        "${currentSeason?.name.orEmpty()} • ${currentEpisode?.name.orEmpty()}"
                                                    }
                                                } else "Полный фильм",
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
                        // Вертикальная компоновка для телефонов
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Выбор сезона и серии для сериалов (для многосерийных фильмов скрываем сезон)
                            if (isSeries && availableSeasons.isNotEmpty()) {
                                if (!detail.isMultiSeriesMovie) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Сезон
                                        Box(modifier = Modifier.weight(1f)) {
                                            Surface(
                                                color = CinemaCard,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, CinemaBorder),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(38.dp)
                                                    .clickable { mobileSeasonDropdownExpanded = true }
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
                                                        imageVector = if (mobileSeasonDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                        contentDescription = null,
                                                        tint = CinemaPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = mobileSeasonDropdownExpanded,
                                                onDismissRequest = { mobileSeasonDropdownExpanded = false },
                                                modifier = Modifier
                                                    .background(CinemaDark)
                                                    .border(1.dp, CinemaPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                                    .widthIn(min = 160.dp, max = 240.dp)
                                                    .heightIn(max = 240.dp)
                                                    .padding(4.dp)
                                            ) {
                                                availableSeasons.forEach { s ->
                                                    val isSelected = s.id == selectedSeasonId
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                text = s.name,
                                                                color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                                fontSize = 12.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                            )
                                                        },
                                                        onClick = {
                                                            selectedSeasonId = s.id
                                                            selectedEpisodeId = s.episodes.firstOrNull()?.id ?: "1"
                                                            mobileSeasonDropdownExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                        // Серия
                                        if (availableEpisodes.isNotEmpty()) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                Surface(
                                                    color = CinemaCard,
                                                    shape = RoundedCornerShape(8.dp),
                                                    border = BorderStroke(1.dp, CinemaBorder),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(38.dp)
                                                        .clickable { mobileEpisodeDropdownExpanded = true }
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
                                                            imageVector = if (mobileEpisodeDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                            contentDescription = null,
                                                            tint = CinemaPrimary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                                DropdownMenu(
                                                    expanded = mobileEpisodeDropdownExpanded,
                                                    onDismissRequest = { mobileEpisodeDropdownExpanded = false },
                                                    modifier = Modifier
                                                        .background(CinemaDark)
                                                        .border(1.dp, CinemaPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                                        .widthIn(min = 160.dp, max = 240.dp)
                                                        .heightIn(max = 240.dp)
                                                        .padding(4.dp)
                                                ) {
                                                    availableEpisodes.forEach { ep ->
                                                        val isSelected = ep.id == selectedEpisodeId
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    text = ep.name,
                                                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                                )
                                                            },
                                                            onClick = {
                                                                selectedEpisodeId = ep.id
                                                                mobileEpisodeDropdownExpanded = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // Многосерийный фильм на телефоне: только выбор серии/части на всю ширину
                                    if (availableEpisodes.isNotEmpty()) {
                                        Box(modifier = Modifier.fillMaxWidth()) {
                                            Surface(
                                                color = CinemaCard,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, CinemaBorder),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(38.dp)
                                                    .clickable { mobileEpisodeDropdownExpanded = true }
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
                                                        imageVector = if (mobileEpisodeDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                        contentDescription = null,
                                                        tint = CinemaPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = mobileEpisodeDropdownExpanded,
                                                onDismissRequest = { mobileEpisodeDropdownExpanded = false },
                                                modifier = Modifier
                                                    .background(CinemaDark)
                                                    .border(1.dp, CinemaPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                                    .widthIn(min = 160.dp, max = 280.dp)
                                                    .heightIn(max = 240.dp)
                                                    .padding(4.dp)
                                            ) {
                                                availableEpisodes.forEach { ep ->
                                                    val isSelected = ep.id == selectedEpisodeId
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                text = ep.name,
                                                                color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                                fontSize = 12.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                            )
                                                        },
                                                        onClick = {
                                                            selectedEpisodeId = ep.id
                                                            mobileEpisodeDropdownExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Выбор озвучки (если озвучек больше 1)
                            if (detail.translators.size > 1) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Surface(
                                        color = CinemaCard,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, CinemaBorder),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(38.dp)
                                            .clickable { mobileTranslatorDropdownExpanded = true }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = selectedTranslator.name,
                                                color = CinemaTextWhite,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                imageVector = if (mobileTranslatorDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                contentDescription = null,
                                                tint = CinemaPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = mobileTranslatorDropdownExpanded,
                                        onDismissRequest = { mobileTranslatorDropdownExpanded = false },
                                        modifier = Modifier
                                            .background(CinemaDark)
                                            .border(1.dp, CinemaPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                            .widthIn(min = 200.dp, max = 300.dp)
                                            .heightIn(max = 240.dp)
                                            .padding(4.dp)
                                    ) {
                                        detail.translators.forEach { trans ->
                                            val isSelected = trans.id == selectedTranslator.id
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = trans.name,
                                                        color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                },
                                                onClick = {
                                                    selectedTranslator = trans
                                                    mobileTranslatorDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Единый цельный блок выбора качества без визуального разделения
                            Surface(
                                color = CinemaCard,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, CinemaBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isLoadingQualities && qualityOptions.isEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 18.dp, horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = CinemaPrimary,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Определение доступного качества...",
                                            color = CinemaTextGray,
                                            fontSize = 14.sp
                                        )
                                    }
                                } else {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        qualityOptions.forEachIndexed { idx, q ->
                                            val isSelected = q == selectedQuality
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(44.dp)
                                                    .background(if (isSelected) CinemaPrimary.copy(alpha = 0.18f) else Color.Transparent)
                                                    .clickable {
                                                        HapticEngine.get().perform(HapticType.SELECTION)
                                                        selectedQuality = q
                                                    }
                                            ) {
                                                Text(
                                                    text = q,
                                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                    fontSize = 15.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                )
                                            }

                                            if (idx < qualityOptions.size - 1) {
                                                HorizontalDivider(
                                                    color = CinemaBorder.copy(alpha = 0.5f),
                                                    thickness = 0.5.dp
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Кнопка: В оффлайн библиотеку
                            Surface(
                                color = CinemaCard,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, CinemaPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clickable { performDownload(true) }
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
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Кнопка: Обычное скачивание
                            Surface(
                                color = CinemaCard,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, CinemaBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clickable { performDownload(false) }
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
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
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

@Composable
fun SeriesDownloadDialog(
    detail: RezkaDetail,
    initialTranslator: Translator?,
    initialSeasonId: Int?,
    initialEpisodeId: String?,
    effectiveSeasons: List<Season>,
    defaultQuality: String,
    initialStreams: List<StreamUrl>? = null,
    fetchStreams: (suspend (translator: Translator, seasonId: Int, episodeId: String) -> List<StreamUrl>)? = null,
    fetchEpisodes: (suspend (translator: Translator) -> List<Season>)? = null,
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
        initialStreams = initialStreams,
        fetchStreams = fetchStreams,
        fetchEpisodes = fetchEpisodes,
        onDismiss = onDismiss,
        onDownload = onDownload
    )
}
