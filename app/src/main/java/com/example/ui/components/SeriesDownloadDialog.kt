package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.example.data.RezkaDetail
import com.example.data.RezkaType
import com.example.data.Season
import com.example.data.Translator
import com.example.ui.theme.*
import com.example.ui.tv.requestFocusSafe
import com.example.ui.tv.tvFocusableItem
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType

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
    var qualityDropdownExpanded by remember { mutableStateOf(false) }

    val initialDialogRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        initialDialogRequester.requestFocusSafe()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CinemaDark,
        title = {
            Text(
                text = "Скачивание",
                color = CinemaTextWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Выбор сезона и серии ТОЛЬКО для сериалов
                if (isSeries) {
                    // 1. Выбор сезона
                    if (availableSeasons.isNotEmpty()) {
                        Column {
                            Text(
                                text = "Сезон",
                                color = CinemaTextGray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CinemaCard, RoundedCornerShape(8.dp))
                                        .tvFocusableItem(
                                            onClick = { seasonDropdownExpanded = true },
                                            shape = RoundedCornerShape(8.dp),
                                            scaleFactor = 1.0f,
                                            focusRequester = initialDialogRequester
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = currentSeason?.name ?: "Сезон $selectedSeasonId",
                                        color = CinemaTextWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        imageVector = if (seasonDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = seasonDropdownExpanded,
                                    onDismissRequest = { seasonDropdownExpanded = false },
                                    modifier = Modifier
                                        .background(CinemaCard)
                                        .fillMaxWidth(0.8f)
                                        .heightIn(max = 240.dp)
                                ) {
                                    availableSeasons.forEach { s ->
                                        val isSelected = s.id == selectedSeasonId
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = s.name,
                                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                selectedSeasonId = s.id
                                                selectedEpisodeId = s.episodes.firstOrNull()?.id ?: "1"
                                                seasonDropdownExpanded = false
                                            },
                                            modifier = Modifier.tvFocusableItem(
                                                onClick = {
                                                    selectedSeasonId = s.id
                                                    selectedEpisodeId = s.episodes.firstOrNull()?.id ?: "1"
                                                    seasonDropdownExpanded = false
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                scaleFactor = 1.0f
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Выбор серии
                    if (availableEpisodes.isNotEmpty()) {
                        Column {
                            Text(
                                text = "Серия",
                                color = CinemaTextGray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CinemaCard, RoundedCornerShape(8.dp))
                                        .tvFocusableItem(
                                            onClick = { episodeDropdownExpanded = true },
                                            shape = RoundedCornerShape(8.dp),
                                            scaleFactor = 1.0f
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = currentEpisode?.name ?: "Серия $selectedEpisodeId",
                                        color = CinemaTextWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        imageVector = if (episodeDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = episodeDropdownExpanded,
                                    onDismissRequest = { episodeDropdownExpanded = false },
                                    modifier = Modifier
                                        .background(CinemaCard)
                                        .fillMaxWidth(0.8f)
                                        .heightIn(max = 240.dp)
                                ) {
                                    availableEpisodes.forEach { ep ->
                                        val isSelected = ep.id == selectedEpisodeId
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = ep.name,
                                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                selectedEpisodeId = ep.id
                                                episodeDropdownExpanded = false
                                            },
                                            modifier = Modifier.tvFocusableItem(
                                                onClick = {
                                                    selectedEpisodeId = ep.id
                                                    episodeDropdownExpanded = false
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                scaleFactor = 1.0f
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Выбор озвучки (и для фильмов, и для сериалов)
                if (detail.translators.isNotEmpty()) {
                    Column {
                        Text(
                            text = "Озвучка",
                            color = CinemaTextGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CinemaCard, RoundedCornerShape(8.dp))
                                    .tvFocusableItem(
                                        onClick = { translatorDropdownExpanded = true },
                                        shape = RoundedCornerShape(8.dp),
                                        scaleFactor = 1.0f,
                                        focusRequester = if (!isSeries || availableSeasons.isEmpty()) initialDialogRequester else null
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f, fill = false),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (selectedTranslator.flagUrl.isNotEmpty()) {
                                        AsyncImage(
                                            model = selectedTranslator.flagUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .padding(end = 8.dp)
                                                .height(14.dp)
                                                .widthIn(max = 22.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                        )
                                    }
                                    Text(
                                        text = selectedTranslator.name,
                                        color = CinemaTextWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (selectedTranslator.isPremium && selectedTranslator.premiumUrl.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        AsyncImage(
                                            model = selectedTranslator.premiumUrl,
                                            contentDescription = "Премиум",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .height(14.dp)
                                                .widthIn(max = 22.dp)
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = if (translatorDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = translatorDropdownExpanded,
                                onDismissRequest = { translatorDropdownExpanded = false },
                                modifier = Modifier
                                    .background(CinemaCard)
                                    .fillMaxWidth(0.8f)
                                    .heightIn(max = 240.dp)
                            ) {
                                detail.translators.forEach { trans ->
                                    val isSelected = trans.id == selectedTranslator.id
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                if (trans.flagUrl.isNotEmpty()) {
                                                    AsyncImage(
                                                        model = trans.flagUrl,
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Fit,
                                                        modifier = Modifier
                                                            .padding(end = 8.dp)
                                                            .height(14.dp)
                                                            .widthIn(max = 22.dp)
                                                            .clip(RoundedCornerShape(2.dp))
                                                    )
                                                }
                                                Text(
                                                    text = trans.name,
                                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (trans.isPremium && trans.premiumUrl.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    AsyncImage(
                                                        model = trans.premiumUrl,
                                                        contentDescription = "Премиум",
                                                        contentScale = ContentScale.Fit,
                                                        modifier = Modifier
                                                            .height(14.dp)
                                                            .widthIn(max = 22.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedTranslator = trans
                                            translatorDropdownExpanded = false
                                        },
                                        modifier = Modifier.tvFocusableItem(
                                            onClick = {
                                                selectedTranslator = trans
                                                translatorDropdownExpanded = false
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            scaleFactor = 1.0f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Выбор качества (и для фильмов, и для сериалов)
                Column {
                    Text(
                        text = "Качество",
                        color = CinemaTextGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CinemaCard, RoundedCornerShape(8.dp))
                                .tvFocusableItem(
                                    onClick = { qualityDropdownExpanded = true },
                                    shape = RoundedCornerShape(8.dp),
                                    scaleFactor = 1.0f
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedQuality,
                                color = CinemaTextWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = if (qualityDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = CinemaPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = qualityDropdownExpanded,
                            onDismissRequest = { qualityDropdownExpanded = false },
                            modifier = Modifier
                                .background(CinemaCard)
                                .fillMaxWidth(0.8f)
                        ) {
                            qualityOptions.forEach { q ->
                                val isSelected = q == selectedQuality
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = q,
                                            color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        selectedQuality = q
                                        qualityDropdownExpanded = false
                                    },
                                    modifier = Modifier.tvFocusableItem(
                                        onClick = {
                                            selectedQuality = q
                                            qualityDropdownExpanded = false
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        scaleFactor = 1.0f
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 5. Кнопки действий прямо внутри контента для идеальной вёрстки
                Spacer(modifier = Modifier.height(6.dp))

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

                // Кнопка 1: "Скачать в оффлайн библиотеку"
                OutlinedButton(
                    onClick = { performDownload(true) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CinemaTextWhite),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .tvFocusableItem(
                            onClick = { performDownload(true) },
                            shape = RoundedCornerShape(8.dp),
                            scaleFactor = 1.0f
                        )
                        .testTag("download_offline_library_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = "Скачать в оффлайн библиотеку",
                        modifier = Modifier.size(18.dp),
                        tint = CinemaPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Скачать в оффлайн библиотеку",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Кнопка 2: "Скачать" (обычный файл)
                OutlinedButton(
                    onClick = { performDownload(false) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CinemaTextWhite),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .tvFocusableItem(
                            onClick = { performDownload(false) },
                            shape = RoundedCornerShape(8.dp),
                            scaleFactor = 1.0f
                        )
                        .testTag("download_regular_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Обычное скачивание",
                        modifier = Modifier.size(18.dp),
                        tint = CinemaPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Скачать",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = {
                    HapticEngine.get().perform(HapticType.GENTLE_TICK)
                    onDismiss()
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = CinemaTextGray),
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusableItem(
                        onClick = {
                            HapticEngine.get().perform(HapticType.GENTLE_TICK)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp),
                        scaleFactor = 1.0f
                    )
            ) {
                Text(text = "Закрыть", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    )
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
