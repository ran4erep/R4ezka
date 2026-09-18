package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.*
import com.example.ui.DetailState
import com.example.ui.RezkaViewModel
import com.example.ui.components.FallingSkullsBufferingOverlay
import com.example.ui.components.RezkaPlayer
import com.example.ui.components.ScheduleCalendarDialog
import com.example.ui.theme.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.example.ui.tv.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun findMatchingEpisode(
    currentEpisodeId: String,
    currentEpisodeName: String,
    targetEpisodes: List<Episode>
): Episode? {
    if (targetEpisodes.isEmpty()) return null

    // 1. Точное совпадение по ID серии
    if (currentEpisodeId.isNotEmpty()) {
        targetEpisodes.find { it.id == currentEpisodeId }?.let { return it }
    }

    // 2. Точное совпадение по названию (без учета регистра)
    val cleanCurrent = currentEpisodeName.trim().lowercase()
    if (cleanCurrent.isNotEmpty()) {
        targetEpisodes.find { it.name.trim().lowercase() == cleanCurrent }?.let { return it }
    }

    // 3. Извлечем все числа из ID и названия текущей серии (поддержка сдвоенных серий 1-2, 3 и т.д.)
    val currentNumbers = (Regex("""\d+""").findAll(currentEpisodeId).mapNotNull { it.value.toIntOrNull() } +
            Regex("""\d+""").findAll(currentEpisodeName).mapNotNull { it.value.toIntOrNull() }).distinct().toList()

    if (currentNumbers.isNotEmpty()) {
        // Ищем серию, у которой ID или название содержит хотя бы одно из чисел
        for (ep in targetEpisodes) {
            val epNumbers = (Regex("""\d+""").findAll(ep.id).mapNotNull { it.value.toIntOrNull() } +
                    Regex("""\d+""").findAll(ep.name).mapNotNull { it.value.toIntOrNull() }).distinct().toList()
            if (epNumbers.any { currentNumbers.contains(it) }) {
                return ep
            }
        }

        // Если в новой озвучке меньше серий (еще не успели перевести), берем последнюю доступную серию
        val maxCurrent = currentNumbers.maxOrNull() ?: 1
        val lastEp = targetEpisodes.lastOrNull()
        if (lastEp != null) {
            val lastEpNumber = Regex("""\d+""").findAll(lastEp.id + " " + lastEp.name)
                .mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: targetEpisodes.size
            if (maxCurrent > lastEpNumber) {
                return lastEp
            }
        }
    }

    return targetEpisodes.firstOrNull()
}

private fun findMatchingEpisode(currentEpisodeName: String, targetEpisodes: List<Episode>): Episode? {
    return findMatchingEpisode("", currentEpisodeName, targetEpisodes)
}

@Composable
fun DetailScreen(
    viewModel: RezkaViewModel,
    item: RezkaItem,
    initialTranslatorId: String? = null,
    onBack: () -> Unit,
    onNavigateToThematic: (String, String) -> Unit = { _, _ -> },
    onNavigateToDetail: (RezkaItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()
    val detailState by viewModel.detailState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val defaultQuality by viewModel.defaultQuality.collectAsState()
    val autoNextEpisode by viewModel.autoNextEpisode.collectAsState()
    val commentsState by viewModel.commentsState.collectAsState()
    val isFavorite = favorites.any { it.id == item.id }

    val tvModePrefString by viewModel.tvModePreference.collectAsState()
    val isTvMode = remember(context, tvModePrefString) {
        val pref = when (tvModePrefString) {
            "force_tv" -> TvModePreference.FORCE_TV
            "force_mobile" -> TvModePreference.FORCE_MOBILE
            else -> TvModePreference.AUTO
        }
        TvDetector.shouldShowTvInterface(context, pref)
    }

    val scope = rememberCoroutineScope()

    // Trigger loading on start
    LaunchedEffect(item.id) {
        viewModel.loadDetail(item.url)
    }

    val activity = context as? Activity

    // Player Trigger States
    var isPlayerOpen by remember { mutableStateOf(false) }
    var activePlayerStreams by remember { mutableStateOf<List<StreamUrl>?>(null) }
    var initialQualityIndex by remember { mutableIntStateOf(0) }
    var playerTitle by remember { mutableStateOf("") }
    var playerSubtitle by remember { mutableStateOf("") }
    var playerStartPosition by remember { mutableStateOf(0L) }
    var isDecryptingStreams by remember { mutableStateOf(false) }
    var playbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Ensure screen orientation is restored to unspecified whenever leaving DetailScreen (if not in TV mode)
    DisposableEffect(isTvMode) {
        onDispose {
            playbackJob?.cancel()
            viewModel.setPlayerActive(false)
            if (!isTvMode) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    LaunchedEffect(isPlayerOpen) {
        viewModel.setPlayerActive(isPlayerOpen)
    }

    // Dialog state for "Ask" quality mode
    var pendingStreamsForDialog by remember { mutableStateOf<List<StreamUrl>?>(null) }
    var pendingPlayTitle by remember { mutableStateOf("") }
    var pendingPlaySubtitle by remember { mutableStateOf("") }
    var pendingPlayStartPos by remember { mutableStateOf(0L) }

    // Active selector states
    var selectedTranslator by remember { mutableStateOf<Translator?>(null) }
    var selectedSeasonId by remember { mutableStateOf<Int?>(null) }
    var selectedEpisodeId by remember { mutableStateOf<String?>(null) }
    var dynamicSeasons by remember { mutableStateOf<List<Season>>(emptyList()) }

    // Deterministic calculation of comments section item index in the LazyColumn
    val commentsSectionIndex by remember(detailState, dynamicSeasons) {
        derivedStateOf {
            val detail = (detailState as? DetailState.Success)?.detail ?: return@derivedStateOf -1
            var idx = 2 // Backdrop (0) + Poster/Meta (1)
            if (detail.translators.isNotEmpty()) {
                idx++
            }
            val effectiveSeasons = if (dynamicSeasons.isNotEmpty()) dynamicSeasons else detail.seasons
            if (detail.type == RezkaType.SERIES && effectiveSeasons.isNotEmpty()) {
                idx += 2 // Seasons selector + Episodes selector
            }
            if (detail.type == RezkaType.MOVIE) {
                idx++ // Play button
            }
            idx
        }
    }

    var showScheduleCalendarDialog by remember { mutableStateOf(false) }
    var isActorsExpanded by remember { mutableStateOf(false) }

    // Intercept system Back button so exiting player returns to movie details, NOT to home/search!
    BackHandler(enabled = isPlayerOpen) {
        playbackJob?.cancel()
        isPlayerOpen = false
        activePlayerStreams = null
        isDecryptingStreams = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // Handle manual back button on movie detail screen
    val handleBack = {
        if (isPlayerOpen) {
            playbackJob?.cancel()
            isPlayerOpen = false
            activePlayerStreams = null
            isDecryptingStreams = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            viewModel.clearDetail()
            onBack()
        }
    }

    // Playback starting logic: Immediately opens player in landscape and fetches streams in background
    val startPlayback = { translator: Translator, seasonId: Int, episodeId: String, customStartPos: Long? ->
        playbackJob?.cancel()

        selectedTranslator = translator
        selectedSeasonId = seasonId
        selectedEpisodeId = episodeId

        val currentDetail = (detailState as? DetailState.Success)?.detail
        val isSeries = currentDetail?.let { it.type == RezkaType.SERIES } ?: (item.type == RezkaType.SERIES)
        val effectiveSeason = if (isSeries) seasonId.coerceAtLeast(1) else 0
        val effectiveEpisode = if (isSeries) (if (episodeId.isBlank() || episodeId == "0") "1" else episodeId) else ""

        val titleText = item.title
        val subtitleText = if (isSeries) {
            "Сезон $effectiveSeason, Серия $effectiveEpisode (${translator.name})"
        } else {
            translator.name
        }

        playerTitle = titleText
        playerSubtitle = subtitleText
        playerStartPosition = customStartPos ?: 0L
        isPlayerOpen = true
        isDecryptingStreams = true

        // Rotate screen immediately to sensor landscape upon click on mobile
        if (!isTvMode) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        playbackJob = scope.launch {
            try {
                val targetId = currentDetail?.numericPostId?.ifEmpty { null } ?: item.id

                // Calculate display episode number and index for robust history matching
                val effectiveSeasons = if (dynamicSeasons.isNotEmpty()) dynamicSeasons else (currentDetail?.seasons ?: emptyList())
                val curSeason = effectiveSeasons.find { it.id == effectiveSeason } ?: effectiveSeasons.firstOrNull()
                val curEpisodes = curSeason?.episodes ?: emptyList()
                val curEpisodeIndex = curEpisodes.indexOfFirst { it.id == effectiveEpisode }
                val curEpisode = curEpisodes.find { it.id == effectiveEpisode }
                    ?: findMatchingEpisode(effectiveEpisode, effectiveEpisode, curEpisodes)
                val displayEpNumber = curEpisode?.id ?: if (effectiveEpisode.isNotBlank() && effectiveEpisode != "0") effectiveEpisode else "1"

                // Check for saved watch progress for this exact season/episode or general movie progress
                val savedHistory = viewModel.getSavedProgressForEpisode(item.id, effectiveSeason, effectiveEpisode)
                    ?: viewModel.getSavedProgressForEpisode(item.id, effectiveSeason, displayEpNumber)
                    ?: viewModel.getSavedProgress(item.id)

                val startPos = if (customStartPos != null) {
                    customStartPos
                } else if (savedHistory != null && 
                    (savedHistory.season == effectiveSeason || !isSeries) && 
                    (savedHistory.episode == effectiveEpisode || savedHistory.episode == displayEpNumber || !isSeries)) {
                    if (savedHistory.durationMs > 0 && savedHistory.progressMs >= savedHistory.durationMs - 5000L) {
                        0L // Reset to start if near end of video
                    } else {
                        savedHistory.progressMs
                    }
                } else {
                    0L
                }

                // Immediately update history so active episode is saved as the latest entry
                if (isSeries) {
                    val priorEpCount = if (curSeason != null) {
                        effectiveSeasons.takeWhile { it.id != curSeason.id }.sumOf { it.episodes.size }
                    } else 0
                    val epNum = curEpisode?.id?.let { id -> Regex("""\d+""").findAll(id).mapNotNull { it.value.toIntOrNull() }.maxOrNull() }
                        ?: Regex("""\d+""").findAll(displayEpNumber).mapNotNull { it.value.toIntOrNull() }.maxOrNull()
                        ?: (if (curEpisodeIndex >= 0) curEpisodeIndex + 1 else 1)
                    val calculatedEpIndex = (priorEpCount + epNum).coerceAtLeast(1)
                    val totalEpCount = effectiveSeasons.sumOf { it.episodes.size }.coerceAtLeast(1)

                    viewModel.saveWatchProgress(
                        itemId = item.id,
                        title = item.title,
                        imageUrl = item.imageUrl,
                        subtitle = "Сезон ${curSeason?.id ?: effectiveSeason}, Серия $displayEpNumber",
                        url = item.url,
                        translatorId = translator.id,
                        translatorName = translator.name,
                        season = curSeason?.id ?: effectiveSeason,
                        episode = displayEpNumber,
                        progressMs = startPos,
                        durationMs = savedHistory?.durationMs ?: 0L,
                        totalEpisodes = totalEpCount,
                        episodeIndex = calculatedEpIndex,
                        totalSeasons = effectiveSeasons.size.coerceAtLeast(1)
                    )
                }

                val streams = viewModel.getStreamUrls(
                    itemId = targetId,
                    translatorId = translator.id,
                    isSeries = isSeries,
                    season = effectiveSeason,
                    episode = effectiveEpisode
                )

                if (streams.isNotEmpty()) {
                    if (defaultQuality == RezkaService.QUALITY_ASK) {
                        // Open Quality Prompt Dialog
                        pendingStreamsForDialog = streams
                        pendingPlayTitle = titleText
                        pendingPlaySubtitle = subtitleText
                        pendingPlayStartPos = startPos
                        isDecryptingStreams = false
                    } else {
                        val chosenIdx = RezkaService.findBestQualityIndex(streams, defaultQuality)
                        playerStartPosition = startPos
                        initialQualityIndex = chosenIdx
                        activePlayerStreams = streams
                        isDecryptingStreams = false
                    }
                } else {
                    isDecryptingStreams = false
                    Toast.makeText(context, "Не удалось получить ссылки на видео", Toast.LENGTH_SHORT).show()
                    isPlayerOpen = false
                    activePlayerStreams = null
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    isDecryptingStreams = false
                    Toast.makeText(context, "Ошибка сети при загрузке плеера", Toast.LENGTH_SHORT).show()
                    isPlayerOpen = false
                    activePlayerStreams = null
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    fun startPlayback(translator: Translator, seasonId: Int, episodeId: String) {
        startPlayback(translator, seasonId, episodeId, null)
    }

    val displayState = remember(detailState, item.id) {
        val state = detailState
        if (state is DetailState.Success &&
            state.detail.id != item.id &&
            RezkaService.extractNumericId(state.detail.id) != RezkaService.extractNumericId(item.id)
        ) {
            DetailState.Loading
        } else {
            state
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        when (val state = displayState) {
            is DetailState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(48.dp))
                }
            }
            is DetailState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Что-то пошло не так :(",
                        color = CinemaTextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = handleBack,
                        colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Назад",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            is DetailState.Success -> {
                val detail = state.detail

                // Автоматически поднимаем список наверх при смене ID фильма (переход по франшизе)
                LaunchedEffect(detail.id) {
                    lazyListState.scrollToItem(0)
                }

                // Automatically restore user's saved selection (translator, season, episode) or deep linked translator
                LaunchedEffect(detail) {
                    if (selectedTranslator == null) {
                        // 1. Наивысший приоритет — озвучка из входящей ссылки (deep link / share)
                        val deepLinkedTranslator = if (!initialTranslatorId.isNullOrEmpty()) {
                            detail.translators.find { it.id == initialTranslatorId }
                        } else null

                        if (deepLinkedTranslator != null) {
                            selectedTranslator = deepLinkedTranslator
                            if (!deepLinkedTranslator.isDefault && detail.type == RezkaType.SERIES && detail.numericPostId.isNotEmpty()) {
                                try {
                                    val fetchedSeasons = viewModel.getEpisodesForTranslator(detail.numericPostId, deepLinkedTranslator.id, deepLinkedTranslator.url)
                                    if (fetchedSeasons.isNotEmpty()) {
                                        dynamicSeasons = fetchedSeasons
                                    }
                                } catch (e: Exception) {
                                    Log.e("DetailScreen", "Error loading seasons for deep linked translator", e)
                                }
                            }
                            val effectiveSeasons = if (dynamicSeasons.isNotEmpty()) dynamicSeasons else detail.seasons
                            if (selectedSeasonId == null && effectiveSeasons.isNotEmpty()) {
                                selectedSeasonId = effectiveSeasons.firstOrNull()?.id
                                selectedEpisodeId = effectiveSeasons.firstOrNull()?.episodes?.firstOrNull()?.id
                            }
                        } else {
                            val savedHistory = viewModel.getSavedProgress(item.id)
                            if (savedHistory != null) {
                            // 1. Restore Translator
                            val restoredTranslator = if (savedHistory.translatorId.isNotEmpty()) {
                                detail.translators.find { it.id == savedHistory.translatorId }
                            } else if (savedHistory.translatorName.isNotEmpty()) {
                                detail.translators.find { it.name.equals(savedHistory.translatorName, ignoreCase = true) }
                            } else null

                            val translatorToUse = restoredTranslator ?: detail.translators.find { it.isDefault } ?: detail.translators.firstOrNull()
                            if (translatorToUse != null) {
                                selectedTranslator = translatorToUse
                                if (!translatorToUse.isDefault && detail.type == RezkaType.SERIES && detail.numericPostId.isNotEmpty()) {
                                    try {
                                        val fetchedSeasons = viewModel.getEpisodesForTranslator(detail.numericPostId, translatorToUse.id, translatorToUse.url)
                                        if (fetchedSeasons.isNotEmpty()) {
                                            dynamicSeasons = fetchedSeasons
                                        }
                                    } catch (e: Exception) {
                                        Log.e("DetailScreen", "Error loading seasons for saved translator", e)
                                    }
                                }
                            }

                            // 2. Restore Season & Episode for series with multi-stage fallback matching
                            if (detail.type == RezkaType.SERIES) {
                                val effectiveSeasons = if (dynamicSeasons.isNotEmpty()) dynamicSeasons else detail.seasons
                                if (effectiveSeasons.isNotEmpty()) {
                                    val savedSeasonId = savedHistory.season
                                    val matchedSeason = effectiveSeasons.find { it.id == savedSeasonId } ?: effectiveSeasons.firstOrNull()
                                    if (matchedSeason != null) {
                                        selectedSeasonId = matchedSeason.id
                                        val savedEpStr = savedHistory.episode

                                        val matchedEp = matchedSeason.episodes.find { it.id == savedEpStr }
                                            ?: findMatchingEpisode(savedEpStr, savedEpStr, matchedSeason.episodes)
                                            ?: run {
                                                val savedNum = savedEpStr.filter { it.isDigit() }.toIntOrNull()
                                                if (savedNum != null && savedNum > 0) {
                                                    matchedSeason.episodes.getOrNull(savedNum - 1)
                                                        ?: matchedSeason.episodes.find { ep ->
                                                            ep.name.filter { it.isDigit() }.toIntOrNull() == savedNum ||
                                                            ep.id.filter { it.isDigit() }.toIntOrNull() == savedNum
                                                        }
                                                } else null
                                            }
                                            ?: matchedSeason.episodes.firstOrNull()

                                        if (matchedEp != null) {
                                            selectedEpisodeId = matchedEp.id
                                        }
                                    }
                                }
                            }
                        } else {
                            // Default selection if no history exists
                            selectedTranslator = detail.translators.find { it.isDefault } ?: detail.translators.firstOrNull()
                            if (selectedSeasonId == null && detail.seasons.isNotEmpty()) {
                                selectedSeasonId = detail.seasons.first().id
                            }
                            if (selectedEpisodeId == null && detail.seasons.isNotEmpty()) {
                                selectedEpisodeId = detail.seasons.firstOrNull()?.episodes?.firstOrNull()?.id
                            }
                        }
                    }
                }
            }

                val effectiveSeasons = if (dynamicSeasons.isNotEmpty()) dynamicSeasons else detail.seasons

                if (isTvMode) {
                    TvDetailContent(
                        detail = detail,
                        item = item,
                        isFavorite = isFavorite,
                        onToggleFavorite = { viewModel.toggleFavorite(item, isFavorite) },
                        selectedTranslator = selectedTranslator,
                        onSelectTranslator = { trans ->
                            selectedTranslator = trans
                            if (detail.type == RezkaType.SERIES) {
                                scope.launch {
                                    val eps = viewModel.getEpisodesForTranslator(detail.numericPostId, trans.id, trans.url)
                                    if (eps.isNotEmpty()) {
                                        val currentSeasonName = effectiveSeasons.find { it.id == selectedSeasonId }?.name ?: ""
                                        val currentEpisode = effectiveSeasons.find { it.id == selectedSeasonId }?.episodes?.find { it.id == selectedEpisodeId }
                                        val currentEpisodeName = currentEpisode?.name ?: ""
                                        val currentEpisodeIdVal = currentEpisode?.id ?: selectedEpisodeId.orEmpty()

                                        dynamicSeasons = eps

                                        val matchedSeason = eps.find { s ->
                                            s.name.trim().lowercase() == currentSeasonName.trim().lowercase() ||
                                            Regex("""\d+""").find(s.name)?.value == Regex("""\d+""").find(currentSeasonName)?.value
                                        } ?: eps.first()

                                        selectedSeasonId = matchedSeason.id

                                        val matchedEpisode = findMatchingEpisode(currentEpisodeIdVal, currentEpisodeName, matchedSeason.episodes)
                                            ?: matchedSeason.episodes.firstOrNull()

                                        selectedEpisodeId = matchedEpisode?.id
                                    }
                                }
                            }
                        },
                        selectedSeasonId = selectedSeasonId,
                        onSelectSeason = { sId ->
                            selectedSeasonId = sId
                            val matchedSeason = effectiveSeasons.find { it.id == sId }
                            selectedEpisodeId = matchedSeason?.episodes?.firstOrNull()?.id
                        },
                        selectedEpisodeId = selectedEpisodeId,
                        onSelectEpisode = { epId -> selectedEpisodeId = epId },
                        effectiveSeasons = effectiveSeasons,
                        commentsState = commentsState,
                        onLoadCommentsPage = { page -> viewModel.loadCommentsPage(page) },
                        onPlayMovie = {
                            val translator = selectedTranslator ?: detail.translators.firstOrNull() ?: Translator("0", "Основной")
                            startPlayback(translator, 0, "")
                        },
                        onPlayEpisode = { season, episode ->
                            val translator = selectedTranslator ?: detail.translators.firstOrNull() ?: Translator("0", "Основной")
                            startPlayback(translator, season.id, episode.id)
                        },
                        onLaunchTrailer = {
                            launchTrailer(
                                context = context,
                                scope = scope,
                                trailerUrl = detail.trailerUrl,
                                numericPostId = detail.numericPostId,
                                title = detail.title,
                                year = detail.year,
                                originalTitle = detail.originalTitle
                            )
                        },
                        onOpenSchedule = { showScheduleCalendarDialog = true },
                        onBack = handleBack,
                        onAppendNextCommentsPage = { viewModel.appendNextCommentsPage() },
                        onNavigateToMovie = { targetItem -> onNavigateToDetail(targetItem) },
                        onNavigateToThematic = { name, url -> onNavigateToThematic(name, url) }
                    )
                } else {
                    // ---- SCROLLABLE MOBILE DETAIL PAGE (with TV/D-Pad support) ----
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .dpadScrollable(lazyListState),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                    // 1. Backdrop Hero Image
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                        ) {
                            AsyncImage(
                                model = detail.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Immersive Dark Overlay gradients
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colorStops = arrayOf(
                                                0.0f to Color.Black.copy(alpha = 0.5f),
                                                0.5f to Color.Transparent,
                                                1.0f to CinemaBlack
                                            )
                                        )
                                    )
                            )
                        }
                    }

                    // 2. Poster, Title, Ratings, Year, Country, Genres, Meta & Description
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = (-44).dp)
                        ) {
                            // Poster + Titles Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Card(
                                    modifier = Modifier
                                        .width(115.dp)
                                        .aspectRatio(0.68f),
                                    shape = RoundedCornerShape(10.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                                ) {
                                    AsyncImage(
                                        model = detail.imageUrl,
                                        contentDescription = detail.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(top = 6.dp)
                                ) {
                                    Text(
                                        text = detail.title,
                                        color = CinemaTextWhite,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Black,
                                        lineHeight = 24.sp
                                    )
                                    if (detail.originalTitle.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = detail.originalTitle,
                                            color = CinemaTextGray,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            lineHeight = 16.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Primary Badges (Year, Clean Age restriction from website)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (detail.year.isNotEmpty()) {
                                            Surface(
                                                color = CinemaCard,
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = detail.year,
                                                    color = CinemaTextWhite,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        }

                                        if (detail.ageRestriction.isNotEmpty()) {
                                            Surface(
                                                color = CinemaPrimary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.4f))
                                            ) {
                                                Text(
                                                    text = detail.ageRestriction,
                                                    color = CinemaPrimary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 4. Main Information Box (Ratings, Release date, Country, Director, Duration, Genres, Franchise)
                            Spacer(modifier = Modifier.height(14.dp))
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                colors = CardDefaults.cardColors(containerColor = CinemaDark),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Оценки
                                    DetailRatingsRow(
                                        ratingInfo = detail.ratingInfo,
                                        fallbackRating = detail.rating
                                    )

                                    val displayDate = detail.releaseDate.ifEmpty { detail.year }
                                    if (displayDate.isNotEmpty()) {
                                        DetailMetaRow(
                                            icon = Icons.Default.DateRange,
                                            label = "Дата выхода:",
                                            value = displayDate
                                        )
                                    }

                                    val displayCountry = detail.countryFlag.ifEmpty { detail.country }
                                    if (displayCountry.isNotEmpty()) {
                                        DetailMetaRow(
                                            icon = Icons.Default.Public,
                                            label = "Страна:",
                                            value = displayCountry
                                        )
                                    }

                                    if (detail.directorsList.isNotEmpty()) {
                                        DetailMetaRowWithLinks(
                                            icon = Icons.Default.MovieFilter,
                                            label = "Режиссёр:",
                                            links = detail.directorsList,
                                            onLinkClick = { link ->
                                                onNavigateToThematic(link.name, link.url)
                                            }
                                        )
                                    } else if (detail.director.isNotEmpty()) {
                                        DetailMetaRow(
                                            icon = Icons.Default.MovieFilter,
                                            label = "Режиссёр:",
                                            value = detail.director
                                        )
                                    }

                                    if (detail.duration.isNotEmpty()) {
                                        DetailMetaRow(
                                            icon = Icons.Default.Schedule,
                                            label = "Время:",
                                            value = detail.duration
                                        )
                                    }

                                    if (detail.genres.isNotEmpty()) {
                                        DetailMetaRow(
                                            icon = Icons.Default.Category,
                                            label = "Жанры:",
                                            value = detail.genres.joinToString(", ")
                                        )
                                    }

                                    if (detail.seriesCollectionList.isNotEmpty()) {
                                        DetailMetaRowWithLinks(
                                            icon = Icons.Default.CollectionsBookmark,
                                            label = "Из серии:",
                                            links = detail.seriesCollectionList,
                                            onLinkClick = { link ->
                                                onNavigateToThematic(link.name, link.url)
                                            }
                                        )
                                    } else if (detail.seriesCollection.isNotEmpty()) {
                                        DetailMetaRow(
                                            icon = Icons.Default.CollectionsBookmark,
                                            label = "Из серии:",
                                            value = detail.seriesCollection
                                        )
                                    }

                                    val collectionsToShow = if (detail.collectionsList.isNotEmpty()) detail.collectionsList else detail.inCollections.map { LinkItem(it, "") }
                                    if (collectionsToShow.isNotEmpty()) {
                                        DetailMetaRowWithLinks(
                                            icon = Icons.Default.FormatListBulleted,
                                            label = "Входит в списки:",
                                            links = collectionsToShow,
                                            onLinkClick = { link ->
                                                onNavigateToThematic(link.name, link.url)
                                            }
                                        )
                                    }

                                    if (detail.slogan.isNotEmpty()) {
                                        DetailMetaRow(
                                            icon = Icons.Default.FormatQuote,
                                            label = "Слоган:",
                                            value = detail.slogan
                                        )
                                    }
                                }
                            }

                            // 7. Actors ("В главных ролях") — Vertical & Expandable
                            if (detail.actors.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(14.dp))
                                val initialActorCount = 4
                                val displayActors = if (isActorsExpanded || detail.actors.size <= initialActorCount) {
                                    detail.actors
                                } else {
                                    detail.actors.take(initialActorCount)
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    colors = CardDefaults.cardColors(containerColor = CinemaDark),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .padding(14.dp)
                                            .animateContentSize()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = detail.actors.size > initialActorCount) {
                                                    isActorsExpanded = !isActorsExpanded
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = CinemaTextGray,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "В главных ролях (${detail.actors.size})",
                                                    color = CinemaTextWhite,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            if (detail.actors.size > initialActorCount) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = if (isActorsExpanded) "Свернуть" else "Показать всех",
                                                        color = CinemaPrimary,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    Icon(
                                                        imageVector = if (isActorsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                        contentDescription = null,
                                                        tint = CinemaPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            val effectiveActors = if (detail.actorsList.isNotEmpty()) {
                                                detail.actorsList
                                            } else {
                                                detail.actors.map { LinkItem(it, "") }
                                            }
                                            val displayActorsList = if (isActorsExpanded || effectiveActors.size <= initialActorCount) {
                                                effectiveActors
                                            } else {
                                                effectiveActors.take(initialActorCount)
                                            }
                                            displayActorsList.forEach { actorLink ->
                                                val isClickable = actorLink.url.isNotEmpty()
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .clickable(enabled = isClickable) {
                                                            onNavigateToThematic(actorLink.name, actorLink.url)
                                                        }
                                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(6.dp)
                                                            .background(if (isClickable) CinemaPrimary else CinemaTextGray, CircleShape)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = actorLink.name,
                                                        color = CinemaTextWhite,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isClickable) FontWeight.Medium else FontWeight.Normal
                                                    )
                                                    if (isClickable) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.OpenInNew,
                                                            contentDescription = null,
                                                            tint = CinemaPrimary.copy(alpha = 0.7f),
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 9. Movie Description
                            Spacer(modifier = Modifier.height(16.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Text(
                                    text = "Описание",
                                    color = CinemaTextWhite,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = detail.description.ifEmpty { "Описание отсутствует." },
                                    color = CinemaTextGray,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp
                                )
                            }

                            // Франшиза/Сага (Все части франшизы)
                            if (detail.franchiseItems.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Text(
                                        text = detail.franchiseTitle.ifEmpty { "Все части франшизы" },
                                        color = CinemaTextWhite,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = CinemaDark),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            detail.franchiseItems.forEach { franchiseItem ->
                                                val isCurrent = franchiseItem.isCurrent
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isCurrent) CinemaPrimary.copy(alpha = 0.15f) else Color.Transparent)
                                                        .clickable(enabled = !isCurrent && franchiseItem.url.isNotEmpty()) {
                                                            val targetItem = RezkaItem(
                                                                id = franchiseItem.id.ifEmpty { franchiseItem.url.hashCode().toString() },
                                                                title = franchiseItem.title,
                                                                subtitle = franchiseItem.year,
                                                                imageUrl = "",
                                                                rating = "",
                                                                url = franchiseItem.url,
                                                                type = detail.type
                                                            )
                                                            onNavigateToDetail(targetItem)
                                                        }
                                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = if (isCurrent) Icons.Default.PlayArrow else Icons.Default.Movie,
                                                        contentDescription = null,
                                                        tint = if (isCurrent) CinemaPrimary else CinemaTextGray,
                                                        modifier = Modifier.size(16.dp)
                                                     )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Text(
                                                        text = franchiseItem.title,
                                                        color = if (isCurrent) CinemaPrimary else CinemaTextWhite,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (franchiseItem.year.isNotEmpty()) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = franchiseItem.year,
                                                            color = if (isCurrent) CinemaPrimary.copy(alpha = 0.8f) else CinemaTextGray,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    }
                                                    if (!isCurrent && franchiseItem.url.isNotEmpty()) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Icon(
                                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                            contentDescription = null,
                                                            tint = CinemaTextGray.copy(alpha = 0.6f),
                                                            modifier = Modifier.size(14.dp)
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

                    // 5. Translators List (Dropdown Selection)
                    if (detail.translators.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 20.dp, start = 16.dp, end = 16.dp)
                            ) {
                                Text(
                                    text = "Озвучка / Перевод",
                                    color = CinemaTextWhite,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                val currentTrans = selectedTranslator ?: detail.translators.first()
                                var translatorDropdownExpanded by remember { mutableStateOf(false) }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(CinemaDark)
                                            .clickable { translatorDropdownExpanded = true }
                                            .padding(horizontal = 14.dp, vertical = 12.dp)
                                            .testTag("translator_dropdown_trigger"),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f, fill = false),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (currentTrans.flagUrl.isNotEmpty()) {
                                                AsyncImage(
                                                    model = currentTrans.flagUrl,
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
                                                text = currentTrans.name,
                                                color = CinemaTextWhite,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (currentTrans.isPremium && currentTrans.premiumUrl.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                AsyncImage(
                                                    model = currentTrans.premiumUrl,
                                                    contentDescription = "Премиум",
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier
                                                        .height(14.dp)
                                                        .widthIn(max = 22.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = if (translatorDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                            contentDescription = "Выбор озвучки",
                                            tint = CinemaPrimary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = translatorDropdownExpanded,
                                        onDismissRequest = { translatorDropdownExpanded = false },
                                        modifier = Modifier
                                            .background(CinemaDark)
                                            .fillMaxWidth(0.9f)
                                            .heightIn(max = 300.dp)
                                    ) {
                                        detail.translators.forEach { trans ->
                                            val isSelected = trans.id == currentTrans.id
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
                                                    translatorDropdownExpanded = false
                                                    if (!isSelected) {
                                                        selectedTranslator = trans
                                                        if (detail.type == RezkaType.SERIES) {
                                                            scope.launch {
                                                                val eps = viewModel.getEpisodesForTranslator(detail.numericPostId, trans.id, trans.url)
                                                                if (eps.isNotEmpty()) {
                                                                    val currentSeasonName = effectiveSeasons.find { it.id == selectedSeasonId }?.name ?: ""
                                                                    val currentEpisode = effectiveSeasons.find { it.id == selectedSeasonId }?.episodes?.find { it.id == selectedEpisodeId }
                                                                    val currentEpisodeName = currentEpisode?.name ?: ""
                                                                    val currentEpisodeIdVal = currentEpisode?.id ?: selectedEpisodeId.orEmpty()

                                                                    dynamicSeasons = eps

                                                                    val matchedSeason = eps.find { s ->
                                                                        s.name.trim().lowercase() == currentSeasonName.trim().lowercase() ||
                                                                        Regex("""\d+""").find(s.name)?.value == Regex("""\d+""").find(currentSeasonName)?.value
                                                                    } ?: eps.first()

                                                                    selectedSeasonId = matchedSeason.id

                                                                    val matchedEpisode = findMatchingEpisode(currentEpisodeIdVal, currentEpisodeName, matchedSeason.episodes)
                                                                        ?: matchedSeason.episodes.firstOrNull()

                                                                    selectedEpisodeId = matchedEpisode?.id
                                                                }
                                                            }
                                                        }
                                                    }
                                                },
                                                colors = MenuDefaults.itemColors(
                                                    textColor = CinemaTextWhite
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 6. Series Navigation Panel (Seasons & Episodes Grid)
                    val effectiveSeasons = if (dynamicSeasons.isNotEmpty()) dynamicSeasons else detail.seasons
                    if (detail.type == RezkaType.SERIES && effectiveSeasons.isNotEmpty()) {
                        // Seasons Dropdown Selector
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 20.dp, start = 16.dp, end = 16.dp)
                            ) {
                                Text(
                                    text = "Сезон",
                                    color = CinemaTextWhite,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                val currentSeason = effectiveSeasons.find { it.id == selectedSeasonId } ?: effectiveSeasons.first()
                                var seasonDropdownExpanded by remember { mutableStateOf(false) }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(CinemaDark)
                                            .clickable { seasonDropdownExpanded = true }
                                            .padding(horizontal = 14.dp, vertical = 12.dp)
                                            .testTag("season_dropdown_trigger"),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = currentSeason.name,
                                            color = CinemaTextWhite,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = if (seasonDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                            contentDescription = "Выбор сезона",
                                            tint = CinemaPrimary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = seasonDropdownExpanded,
                                        onDismissRequest = { seasonDropdownExpanded = false },
                                        modifier = Modifier
                                            .background(CinemaDark)
                                            .fillMaxWidth(0.9f)
                                            .heightIn(max = 300.dp)
                                    ) {
                                        effectiveSeasons.forEach { s ->
                                            val isSelected = s.id == currentSeason.id
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
                                                    seasonDropdownExpanded = false
                                                    if (!isSelected) {
                                                        selectedSeasonId = s.id
                                                        selectedEpisodeId = s.episodes.firstOrNull()?.id
                                                    }
                                                },
                                                colors = MenuDefaults.itemColors(
                                                    textColor = CinemaTextWhite
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Episodes Grid Selector
                        val currentSeason = effectiveSeasons.find { it.id == selectedSeasonId } ?: effectiveSeasons.first()
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 20.dp, start = 16.dp, end = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Серии (${currentSeason.episodes.size})",
                                        color = CinemaTextWhite,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Кнопка Трейлер
                                        Surface(
                                            onClick = {
                                                launchTrailer(
                                                    context = context,
                                                    scope = scope,
                                                    trailerUrl = detail.trailerUrl,
                                                    numericPostId = detail.numericPostId,
                                                    title = detail.title,
                                                    year = detail.year,
                                                    originalTitle = detail.originalTitle
                                                )
                                            },
                                            color = CinemaCard,
                                            shape = RoundedCornerShape(8.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF0000).copy(alpha = 0.5f)),
                                            modifier = Modifier.testTag("trailer_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                YouTubeLogoIcon(width = 18.dp, height = 13.dp, playIconSize = 9.dp)
                                                Text(
                                                    text = "Трейлер",
                                                    color = CinemaTextWhite,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        if (detail.schedule.isNotEmpty()) {
                                            Surface(
                                                onClick = { showScheduleCalendarDialog = true },
                                                color = CinemaCard,
                                                shape = RoundedCornerShape(8.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.CalendarMonth,
                                                        contentDescription = null,
                                                        tint = CinemaPrimary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "Расписание",
                                                        color = CinemaTextWhite,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    if (detail.schedule.any { !it.isReleased }) {
                                                        Spacer(modifier = Modifier.width(5.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .size(6.dp)
                                                                .background(CinemaAmber, CircleShape)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))

                                // Fast grid of episodes in Rows (2-columns) to minimize heavy compositions
                                val chunks = currentSeason.episodes.chunked(2)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    chunks.forEach { rowEpisodes ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowEpisodes.forEach { ep ->
                                                val isSelected = selectedEpisodeId == ep.id
                                                val bgAnimate by animateColorAsState(
                                                    targetValue = if (isSelected) CinemaPrimary.copy(alpha = 0.1f) else CinemaDark,
                                                    animationSpec = tween(200)
                                                )

                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(bgAnimate)
                                                        .clickable {
                                                            selectedEpisodeId = ep.id
                                                            val translator = selectedTranslator ?: detail.translators.firstOrNull() ?: Translator("0", "Основной")
                                                            startPlayback(translator, currentSeason.id, ep.id)
                                                        }
                                                        .padding(horizontal = 12.dp, vertical = 14.dp),
                                                    contentAlignment = Alignment.CenterStart
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = ep.name,
                                                            color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        if (isSelected) {
                                                            Icon(
                                                                imageVector = Icons.Default.PlayArrow,
                                                                contentDescription = null,
                                                                tint = CinemaPrimary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            if (rowEpisodes.size < 2) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 7. Cinema Play Movie button & Trailer button
                    if (detail.type == RezkaType.MOVIE) {
                        item {
                            Spacer(modifier = Modifier.height(28.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        val translator = selectedTranslator ?: detail.translators.firstOrNull() ?: Translator("0", "Основной")
                                        startPlayback(translator, 0, "")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1.35f)
                                        .height(52.dp)
                                        .tvFocusableItem(
                                            onClick = {
                                                val translator = selectedTranslator ?: detail.translators.firstOrNull() ?: Translator("0", "Основной")
                                                startPlayback(translator, 0, "")
                                            },
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .testTag("movie_play_button")
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("СМОТРЕТЬ", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }

                                 Surface(
                                    onClick = {
                                        launchTrailer(
                                            context = context,
                                            scope = scope,
                                            trailerUrl = detail.trailerUrl,
                                            numericPostId = detail.numericPostId,
                                            title = detail.title,
                                            year = detail.year,
                                            originalTitle = detail.originalTitle
                                        )
                                    },
                                    color = CinemaCard,
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF0000).copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .tvFocusableItem(
                                            onClick = {
                                                launchTrailer(
                                                    context = context,
                                                    scope = scope,
                                                    trailerUrl = detail.trailerUrl,
                                                    numericPostId = detail.numericPostId,
                                                    title = detail.title,
                                                    year = detail.year,
                                                    originalTitle = detail.originalTitle
                                                )
                                            },
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .testTag("trailer_button")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        YouTubeLogoIcon(width = 22.dp, height = 15.dp, playIconSize = 11.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Трейлер",
                                            color = CinemaTextWhite,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 8. Comments / Reviews Section (Отзывы о фильме/сериале с пагинацией)
                    val displayComments = if (commentsState.comments.isNotEmpty()) commentsState.comments else detail.comments
                    val totalPages = maxOf(commentsState.totalPages, detail.commentsTotalPages, commentsState.currentPage)
                    val hasPagination = totalPages > 1 || commentsState.hasMore || commentsState.currentPage > 1
                    val totalReviewsCount = when {
                        commentsState.totalCount > 0 -> commentsState.totalCount
                        detail.commentsTotalCount > 0 -> detail.commentsTotalCount
                        else -> displayComments.size
                    }

                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp, bottom = 32.dp, start = 16.dp, end = 16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.RateReview,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (totalReviewsCount > 0) "Отзывы зрителей ($totalReviewsCount)" else "Отзывы зрителей",
                                        color = CinemaTextWhite,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (totalPages > 1) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "• Стр. ${commentsState.currentPage} из $totalPages",
                                            color = CinemaTextGray,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Normal
                                        )
                                    }
                                }

                                if (commentsState.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = CinemaPrimary
                                    )
                                }
                            }
                        }
                    }

                    if (commentsState.isLoading && displayComments.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp, horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = CinemaPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Загрузка отзывов...",
                                        color = CinemaTextGray,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    } else if (displayComments.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                colors = CardDefaults.cardColors(containerColor = CinemaDark),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 20.dp, horizontal = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Отзывов пока нет. Вы можете оставить первый отзыв на сайте!",
                                        color = CinemaTextGray,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        items(
                            items = displayComments,
                            key = { comment -> comment.id }
                        ) { comment ->
                                        val startIndent = (comment.indent.coerceAtMost(3) * 14).dp
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = startIndent)
                                        ) {
                                            if (comment.indent > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(2.dp)
                                                        .height(48.dp)
                                                        .background(CinemaSecondary.copy(alpha = 0.5f))
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }

                                            Card(
                                                modifier = Modifier.weight(1f),
                                                colors = CardDefaults.cardColors(containerColor = CinemaDark),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        // Высокопроизводительная аватарка пользователя с сайта HDRezka
                                                        CommentUserAvatar(
                                                            avatarUrl = comment.avatarUrl,
                                                            authorName = comment.author,
                                                            modifier = Modifier.size(38.dp)
                                                        )

                                                        Spacer(modifier = Modifier.width(10.dp))

                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = comment.author,
                                                                color = CinemaTextWhite,
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            if (comment.date.isNotEmpty()) {
                                                                Text(
                                                                    text = comment.date,
                                                                    color = CinemaTextGray,
                                                                    fontSize = 11.sp
                                                                )
                                                            }
                                                        }

                                                        if (comment.likes.isNotEmpty() && comment.likes != "(0)" && comment.likes != "0") {
                                                            Surface(
                                                                color = CinemaCard,
                                                                shape = RoundedCornerShape(12.dp)
                                                            ) {
                                                                Row(
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Icon(
                                                                        Icons.Default.ThumbUp,
                                                                        contentDescription = null,
                                                                        tint = CinemaPrimary,
                                                                        modifier = Modifier.size(12.dp)
                                                                    )
                                                                    Spacer(modifier = Modifier.width(4.dp))
                                                                    Text(
                                                                        text = comment.likes.replace("(", "").replace(")", ""),
                                                                        color = CinemaTextWhite,
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.Bold
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.height(10.dp))

                                                    Text(
                                                        text = comment.text,
                                                        color = CinemaTextWhite.copy(alpha = 0.9f),
                                                        fontSize = 13.sp,
                                                        lineHeight = 18.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Блок полноценной пагинации отзывов с выбором страниц и догрузкой
                    if (hasPagination) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 18.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // 1. Кнопка "Загрузить ещё отзывы" в текущий список
                                if (commentsState.hasMore || commentsState.currentPage < totalPages) {
                                    Button(
                                        onClick = {
                                            viewModel.appendNextCommentsPage()
                                        },
                                        enabled = !commentsState.isLoading && !commentsState.isLoadingMore,
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CinemaCard,
                                            contentColor = CinemaTextWhite
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.35f))
                                    ) {
                                        if (commentsState.isLoadingMore) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                color = CinemaPrimary,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Загрузка следующих отзывов...", fontSize = 13.sp)
                                        } else {
                                            Icon(
                                                Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = CinemaPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Загрузить ещё отзывы (Стр. ${commentsState.currentPage + 1})",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }

                                // 2. Постраничная панель (Номера страниц [1] [2] [3]... и стрелки Назад/Вперёд)
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = CinemaDark),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // Кнопка "Назад"
                                        IconButton(
                                            onClick = {
                                                viewModel.loadCommentsPage(commentsState.currentPage - 1); scope.launch { lazyListState.animateScrollToItem(commentsSectionIndex) }
                                            },
                                            enabled = commentsState.currentPage > 1 && !commentsState.isLoading && !commentsState.isLoadingMore
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Предыдущая страница",
                                                tint = if (commentsState.currentPage > 1) CinemaTextWhite else CinemaTextGray.copy(alpha = 0.4f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        // Горизонтальный список номеров страниц
                                        LazyRow(
                                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val pagesList = (1..totalPages).toList()
                                            items(pagesList) { pageNum ->
                                                val isSelected = pageNum == commentsState.currentPage
                                                Box(
                                                    modifier = Modifier
                                                        .padding(horizontal = 3.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) CinemaPrimary else CinemaCard)
                                                        .border(
                                                            1.dp,
                                                            if (isSelected) CinemaPrimary else CinemaSecondary.copy(alpha = 0.3f),
                                                            RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable(enabled = !isSelected && !commentsState.isLoading) {
                                                            viewModel.loadCommentsPage(pageNum); scope.launch { lazyListState.animateScrollToItem(commentsSectionIndex) }
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = pageNum.toString(),
                                                        color = if (isSelected) CinemaTextWhite else CinemaTextGray,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }

                                        // Кнопка "Вперёд"
                                        IconButton(
                                            onClick = {
                                                viewModel.loadCommentsPage(commentsState.currentPage + 1); scope.launch { lazyListState.animateScrollToItem(commentsSectionIndex) }
                                            },
                                            enabled = (commentsState.hasMore || commentsState.currentPage < totalPages) && !commentsState.isLoading && !commentsState.isLoadingMore
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = "Следующая страница",
                                                tint = if (commentsState.hasMore || commentsState.currentPage < totalPages) CinemaTextWhite else CinemaTextGray.copy(alpha = 0.4f),
                                                modifier = Modifier.size(20.dp)
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
        is DetailState.Idle -> {}
    }

        // Floating Top Buttons: Кнопки "Назад" и "В избранное" для мобильного режима (плавающие в верхних углах)
        if (!isPlayerOpen && !isTvMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Плавающая кнопка Назад (слева)
                IconButton(
                    onClick = handleBack,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(46.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .testTag("floating_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = CinemaTextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Кнопки действий справа (Поделиться и В избранное)
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Плавающая кнопка "Поделиться"
                    IconButton(
                        onClick = {
                            val shareUrl = RezkaService.buildShareUrl(
                                itemUrl = item.url,
                                translatorId = selectedTranslator?.id
                            )
                            val movieTitle = (detailState as? DetailState.Success)?.detail?.title?.ifEmpty { item.title } ?: item.title
                            val shareText = if (movieTitle.isNotEmpty()) {
                                val translatorSuffix = selectedTranslator?.name?.let { " ($it)" } ?: ""
                                "$movieTitle$translatorSuffix\n$shareUrl"
                            } else {
                                shareUrl
                            }
                            try {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, movieTitle)
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                val chooserIntent = Intent.createChooser(sendIntent, "Поделиться фильмом")
                                context.startActivity(chooserIntent)
                            } catch (e: Exception) {
                                Log.e("DetailScreen", "Error sharing movie link", e)
                                Toast.makeText(context, "Не удалось открыть меню отправки", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            .testTag("share_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Поделиться",
                            tint = CinemaTextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Плавающая кнопка В избранное (справа)
                    IconButton(
                        onClick = { viewModel.toggleFavorite(item, isFavorite) },
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            .testTag("favorite_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Избранное",
                            tint = if (isFavorite) CinemaPrimary else CinemaTextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // Dialog for "Ask" quality selection before starting playback
        pendingStreamsForDialog?.let { streams ->
            AlertDialog(
                onDismissRequest = { pendingStreamsForDialog = null },
                title = {
                    Text(
                        text = "Выберите качество",
                        color = CinemaTextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                containerColor = CinemaDark,
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        streams.forEachIndexed { idx, stream ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        playerTitle = pendingPlayTitle
                                        playerSubtitle = pendingPlaySubtitle
                                        playerStartPosition = pendingPlayStartPos
                                        initialQualityIndex = idx
                                        activePlayerStreams = streams
                                        isPlayerOpen = true
                                        pendingStreamsForDialog = null
                                    }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.HighQuality,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = stream.quality,
                                        color = CinemaTextWhite,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingStreamsForDialog = null
                        if (activePlayerStreams == null) {
                            isPlayerOpen = false
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }) {
                        Text("Отмена", color = CinemaTextGray)
                    }
                }
            )
        }

        // Schedule Calendar Dialog
        if (showScheduleCalendarDialog) {
            val sched = (detailState as? DetailState.Success)?.detail?.schedule ?: emptyList()
            if (sched.isNotEmpty()) {
                ScheduleCalendarDialog(
                    schedule = sched,
                    onDismiss = { showScheduleCalendarDialog = false }
                )
            }
        }

        // ---- FULLSCREEN EXOPLAYER WRAPPER ----
        if (isPlayerOpen) {
            val streams = activePlayerStreams ?: emptyList()
            val currentDetail = (detailState as? DetailState.Success)?.detail
            val isSeries = currentDetail?.let { it.type == RezkaType.SERIES } ?: (item.type == RezkaType.SERIES)
            val seasonsList = if (dynamicSeasons.isNotEmpty()) dynamicSeasons else (currentDetail?.seasons ?: emptyList())
            val curSeason = seasonsList.find { it.id == selectedSeasonId } ?: seasonsList.firstOrNull()
            val curEpisodes = curSeason?.episodes ?: emptyList()
            val curEpisodeIndex = curEpisodes.indexOfFirst { it.id == selectedEpisodeId }

            val hasPreviousEpisode = if (isSeries && curSeason != null) {
                if (curEpisodeIndex > 0) {
                    true
                } else {
                    val sIdx = seasonsList.indexOfFirst { it.id == curSeason.id }
                    sIdx > 0 && seasonsList[sIdx - 1].episodes.isNotEmpty()
                }
            } else false

            val hasNextEpisode = if (isSeries && curSeason != null) {
                if (curEpisodeIndex >= 0 && curEpisodeIndex < curEpisodes.size - 1) {
                    true
                } else {
                    val sIdx = seasonsList.indexOfFirst { it.id == curSeason.id }
                    sIdx >= 0 && sIdx < seasonsList.size - 1 && seasonsList[sIdx + 1].episodes.isNotEmpty()
                }
            } else false

            RezkaPlayer(
                title = playerTitle,
                subtitle = playerSubtitle,
                streams = streams,
                isLoading = isDecryptingStreams,
                isTvMode = isTvMode,
                translators = currentDetail?.translators ?: emptyList(),
                currentTranslator = selectedTranslator ?: currentDetail?.translators?.firstOrNull(),
                onSelectTranslator = { newTrans, currentPosMs ->
                    selectedTranslator = newTrans
                    if (isSeries && currentDetail != null && currentDetail.numericPostId.isNotEmpty()) {
                        scope.launch {
                            isDecryptingStreams = true
                            try {
                                val fetchedSeasons = viewModel.getEpisodesForTranslator(currentDetail.numericPostId, newTrans.id, newTrans.url)
                                val effectiveSeasonsList = if (fetchedSeasons.isNotEmpty()) {
                                    dynamicSeasons = fetchedSeasons
                                    fetchedSeasons
                                } else {
                                    seasonsList
                                }

                                val currentSeasonName = curSeason?.name ?: ""
                                val currentEpisode = curEpisodes.getOrNull(curEpisodeIndex)
                                val currentEpisodeName = currentEpisode?.name ?: ""
                                val currentEpisodeIdVal = currentEpisode?.id ?: ""

                                val targetSeason = effectiveSeasonsList.find { s ->
                                    s.name.trim().lowercase() == currentSeasonName.trim().lowercase() ||
                                    Regex("""\d+""").find(s.name)?.value == Regex("""\d+""").find(currentSeasonName)?.value
                                } ?: effectiveSeasonsList.firstOrNull()

                                val targetSeasonId = targetSeason?.id ?: curSeason?.id ?: 1

                                val targetEpisode = if (targetSeason != null) {
                                    findMatchingEpisode(currentEpisodeIdVal, currentEpisodeName, targetSeason.episodes) ?: targetSeason.episodes.firstOrNull()
                                } else null

                                val targetEpisodeId = targetEpisode?.id ?: currentEpisodeIdVal.ifEmpty { "1" }

                                selectedSeasonId = targetSeasonId
                                selectedEpisodeId = targetEpisodeId
                                startPlayback(newTrans, targetSeasonId, targetEpisodeId, currentPosMs)
                            } catch (e: Exception) {
                                startPlayback(newTrans, curSeason?.id ?: 1, curEpisodes.getOrNull(curEpisodeIndex)?.id ?: "1", currentPosMs)
                            }
                        }
                    } else {
                        startPlayback(newTrans, 0, "", currentPosMs)
                    }
                },
                initialQualityIndex = initialQualityIndex,
                startPositionMs = playerStartPosition,
                isSeries = isSeries,
                hasPreviousEpisode = hasPreviousEpisode,
                hasNextEpisode = hasNextEpisode,
                autoNextEpisode = autoNextEpisode,
                onPreviousEpisode = {
                    if (curSeason != null) {
                        val translator = selectedTranslator ?: currentDetail?.translators?.firstOrNull() ?: Translator("0", "Основной")
                        if (curEpisodeIndex > 0) {
                            val prevEp = curEpisodes[curEpisodeIndex - 1]
                            selectedEpisodeId = prevEp.id
                            startPlayback(translator, curSeason.id, prevEp.id)
                        } else {
                            val sIdx = seasonsList.indexOfFirst { it.id == curSeason.id }
                            if (sIdx > 0) {
                                val prevSeason = seasonsList[sIdx - 1]
                                val lastEp = prevSeason.episodes.lastOrNull()
                                if (lastEp != null) {
                                    selectedSeasonId = prevSeason.id
                                    selectedEpisodeId = lastEp.id
                                    startPlayback(translator, prevSeason.id, lastEp.id)
                                }
                            }
                        }
                    }
                },
                onNextEpisode = {
                    if (curSeason != null) {
                        val translator = selectedTranslator ?: currentDetail?.translators?.firstOrNull() ?: Translator("0", "Основной")
                        if (curEpisodeIndex >= 0 && curEpisodeIndex < curEpisodes.size - 1) {
                            val nextEp = curEpisodes[curEpisodeIndex + 1]
                            selectedEpisodeId = nextEp.id
                            startPlayback(translator, curSeason.id, nextEp.id)
                        } else {
                            val sIdx = seasonsList.indexOfFirst { it.id == curSeason.id }
                            if (sIdx >= 0 && sIdx < seasonsList.size - 1) {
                                val nextSeason = seasonsList[sIdx + 1]
                                val firstEp = nextSeason.episodes.firstOrNull()
                                if (firstEp != null) {
                                    selectedSeasonId = nextSeason.id
                                    selectedEpisodeId = firstEp.id
                                    startPlayback(translator, nextSeason.id, firstEp.id)
                                }
                            }
                        }
                    }
                },
                onBack = {
                    playbackJob?.cancel()
                    isPlayerOpen = false
                    activePlayerStreams = null
                    isDecryptingStreams = false
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    FirebaseSyncManager.flushPendingProgress()
                },
                onProgressUpdate = { pos, duration ->
                    // Auto save progress to DB periodically (when pos > 0)
                    if (pos > 1000) {
                        val effectiveSeasons = if (dynamicSeasons.isNotEmpty()) dynamicSeasons else (currentDetail?.seasons ?: emptyList())
                        val totalEpCount = if (isSeries) {
                            val count = effectiveSeasons.sumOf { it.episodes.size }
                            if (count > 0) count else 1
                        } else 1

                        val curSeason = effectiveSeasons.find { it.id == selectedSeasonId } ?: effectiveSeasons.firstOrNull()
                        val curEpisodes = curSeason?.episodes ?: emptyList()
                        val curEpisode = curEpisodes.find { it.id == selectedEpisodeId }
                            ?: findMatchingEpisode(selectedEpisodeId.orEmpty(), selectedEpisodeId.orEmpty(), curEpisodes)
                        val curEpisodeIndex = curEpisodes.indexOfFirst { it.id == (curEpisode?.id ?: selectedEpisodeId) }

                        val priorEpCount = if (isSeries && curSeason != null) {
                            effectiveSeasons.takeWhile { it.id != curSeason.id }.sumOf { it.episodes.size }
                        } else 0

                        val displayEpNumber = curEpisode?.id ?: selectedEpisodeId?.ifEmpty { "1" } ?: "1"

                        val epNum = curEpisode?.id?.let { id -> Regex("""\d+""").findAll(id).mapNotNull { it.value.toIntOrNull() }.maxOrNull() }
                            ?: Regex("""\d+""").findAll(displayEpNumber).mapNotNull { it.value.toIntOrNull() }.maxOrNull()
                            ?: (if (curEpisodeIndex >= 0) curEpisodeIndex + 1 else 1)

                        val calculatedEpisodeIndex = if (isSeries) {
                            (priorEpCount + epNum).coerceAtLeast(1)
                        } else 1

                        val totalSeasonsCount = if (isSeries) effectiveSeasons.size else 1

                        viewModel.saveWatchProgress(
                            itemId = item.id,
                            title = item.title,
                            imageUrl = item.imageUrl,
                            subtitle = if (isSeries) "Сезон ${curSeason?.id ?: selectedSeasonId ?: 1}, Серия $displayEpNumber" else "Фильм",
                            url = item.url,
                            translatorId = selectedTranslator?.id ?: "",
                            translatorName = selectedTranslator?.name ?: "Дубляж",
                            season = if (isSeries) (curSeason?.id ?: selectedSeasonId ?: 1) else 0,
                            episode = if (isSeries) displayEpNumber else "",
                            progressMs = pos,
                            durationMs = duration,
                            totalEpisodes = totalEpCount,
                            episodeIndex = calculatedEpisodeIndex,
                            totalSeasons = totalSeasonsCount
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Кнопка быстрой прокрутки вверх к началу комментариев
        val showScrollToTop by remember {
            derivedStateOf {
                if (isPlayerOpen) return@derivedStateOf false
                val success = detailState as? DetailState.Success ?: return@derivedStateOf false
                if (commentsSectionIndex < 0) return@derivedStateOf false

                val displayComments = if (commentsState.comments.isNotEmpty()) commentsState.comments else success.detail.comments
                if (displayComments.isEmpty()) return@derivedStateOf false

                val firstIndex = lazyListState.firstVisibleItemIndex
                val firstOffset = lazyListState.firstVisibleItemScrollOffset

                // Кнопка отображается только когда пользователь углубился в чтение комментариев
                firstIndex > commentsSectionIndex || (firstIndex == commentsSectionIndex && firstOffset > 300)
            }
        }

        AnimatedVisibility(
            visible = showScrollToTop,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 24.dp, end = 24.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        if (commentsSectionIndex >= 0) {
                            lazyListState.animateScrollToItem(commentsSectionIndex)
                        }
                    }
                },
                containerColor = CinemaPrimary,
                contentColor = CinemaTextWhite,
                shape = CircleShape,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("comments_scroll_to_top_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Вверх к началу отзывов",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailMetaRowWithLinks(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    links: List<LinkItem>,
    onLinkClick: (LinkItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CinemaTextGray,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = CinemaTextGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(90.dp)
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            links.forEach { link ->
                Surface(
                    color = CinemaDark,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CinemaBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onLinkClick(link) }
                ) {
                    Text(
                        text = link.name,
                        color = CinemaTextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DetailMetaRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CinemaTextGray,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = CinemaTextGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(90.dp)
        )
        Text(
            text = value,
            color = CinemaTextWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun DetailRatingsRow(
    ratingInfo: RatingInfo,
    fallbackRating: String,
    modifier: Modifier = Modifier
) {
    val hasImdb = ratingInfo.imdb.isNotBlank()
    val hasKp = ratingInfo.kinopoisk.isNotBlank()
    val hasRezka = ratingInfo.rezka.isNotBlank() || (fallbackRating.isNotBlank() && !hasImdb && !hasKp)

    if (!hasImdb && !hasKp && !hasRezka) return

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = CinemaAmber,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "В рейтинге:",
            color = CinemaTextGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(90.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (hasImdb) {
                RatingLineItem(
                    label = "IMDb",
                    score = ratingInfo.imdb,
                    votes = ratingInfo.imdbVotes,
                    scoreColor = Color(0xFFF5C518),
                    badgeBg = Color(0xFFF5C518).copy(alpha = 0.15f),
                    badgeBorder = Color(0xFFF5C518).copy(alpha = 0.4f)
                )
            }

            if (hasKp) {
                RatingLineItem(
                    label = "Кинопоиск",
                    score = ratingInfo.kinopoisk,
                    votes = ratingInfo.kinopoiskVotes,
                    scoreColor = Color(0xFFFF6600),
                    badgeBg = Color(0xFFFF6600).copy(alpha = 0.15f),
                    badgeBorder = Color(0xFFFF6600).copy(alpha = 0.4f)
                )
            }

            if (hasRezka) {
                val rezkaVal = ratingInfo.rezka.ifEmpty { fallbackRating }
                RatingLineItem(
                    label = "HDRezka",
                    score = rezkaVal,
                    votes = ratingInfo.rezkaVotes,
                    scoreColor = CinemaPrimary,
                    badgeBg = CinemaPrimary.copy(alpha = 0.15f),
                    badgeBorder = CinemaPrimary.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun RatingLineItem(
    label: String,
    score: String,
    votes: String,
    scoreColor: Color,
    badgeBg: Color,
    badgeBorder: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = badgeBg,
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, badgeBorder)
        ) {
            Text(
                text = label,
                color = scoreColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Text(
            text = score,
            color = CinemaTextWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )

        if (votes.isNotBlank()) {
            val formattedVotes = if (votes.startsWith("(") && votes.endsWith(")")) votes else "($votes)"
            Text(
                text = formattedVotes,
                color = CinemaTextGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Аутентичный фирменный логотип YouTube (красный скруглённый прямоугольник с белым треугольником воспроизведения).
 */
@Composable
fun YouTubeLogoIcon(
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 22.dp,
    height: androidx.compose.ui.unit.Dp = 15.dp,
    playIconSize: androidx.compose.ui.unit.Dp = 10.dp
) {
    Surface(
        modifier = modifier.size(width = width, height = height),
        color = Color(0xFFFF0000),
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(playIconSize)
            )
        }
    }
}

/**
 * Высокопроизводительный запуск реального YouTube видеоролика трейлера.
 * Никогда не открывает поисковую выдачу, а воспроизводит конкретное видео напрямую.
 */
fun launchTrailer(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    trailerUrl: String,
    numericPostId: String = "",
    title: String = "",
    year: String = "",
    originalTitle: String = ""
) {
    // 1. Если ссылка/ID уже были в данных карточки фильма
    val videoId = if (trailerUrl.isNotBlank()) RezkaService.extractYouTubeVideoId(trailerUrl) else null
    if (videoId != null) {
        openYouTubeVideo(context, videoId)
        return
    }

    // 2. Если трейлер подгружается динамически через AJAX или требует резолва
    Toast.makeText(context, "Запуск трейлера...", Toast.LENGTH_SHORT).show()
    scope.launch {
        var resolvedUrl = ""

        // Запрос к AJAX серверу HDRezka
        if (numericPostId.isNotBlank()) {
            try {
                resolvedUrl = RezkaService.fetchTrailerAjax(numericPostId)
            } catch (_: Exception) {}
        }

        // Если на сервере HDRezka трейлер не прикреплен, резолвим точное видео с официального YouTube
        if (resolvedUrl.isBlank()) {
            try {
                resolvedUrl = RezkaService.resolveYouTubeTrailer(title, originalTitle, year)
            } catch (_: Exception) {}
        }

        val resolvedId = if (resolvedUrl.isNotBlank()) RezkaService.extractYouTubeVideoId(resolvedUrl) else null
        withContext(Dispatchers.Main) {
            if (resolvedId != null) {
                openYouTubeVideo(context, resolvedId)
            } else {
                Toast.makeText(context, "Трейлер для этого фильма не найден", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * Непосредственный запуск конкретного видеоролика YouTube в приложении или браузере.
 */
private fun openYouTubeVideo(context: android.content.Context, videoId: String) {
    try {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
            putExtra("force_fullscreen", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(appIntent)
    } catch (_: Exception) {
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "Не удалось открыть видео", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Высокопроизводительный компонент аватарки пользователя с автоматической загрузкой с сайта HDRezka,
 * кэшированием в Coil и детерминированным цветным бейджем с первой буквой автора.
 */
@Composable
fun CommentUserAvatar(
    avatarUrl: String,
    authorName: String,
    modifier: Modifier = Modifier
) {
    val initial = remember(authorName) {
        authorName.trim().take(1).uppercase().ifEmpty { "?" }
    }
    val avatarBg = remember(authorName) {
        val hash = kotlin.math.abs(authorName.hashCode())
        val palette = listOf(
            Color(0xFFE50914), // Red
            Color(0xFF1E88E5), // Blue
            Color(0xFF43A047), // Green
            Color(0xFF8E24AA), // Purple
            Color(0xFFFB8C00), // Orange
            Color(0xFF00ACC1), // Teal
            Color(0xFFD81B60), // Pink
            Color(0xFF3949AB)  // Indigo
        )
        palette[hash % palette.size]
    }

    if (avatarUrl.isNotEmpty() && !avatarUrl.contains("noavatar")) {
        val context = LocalContext.current
        val imageRequest = remember(avatarUrl) {
            ImageRequest.Builder(context)
                .data(avatarUrl)
                .crossfade(true)
                .build()
        }
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(avatarBg, avatarBg.copy(alpha = 0.7f))
                    )
                )
                .border(1.dp, CinemaPrimary.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                color = CinemaTextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            AsyncImage(
                model = imageRequest,
                contentDescription = "Аватар $authorName",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(avatarBg, avatarBg.copy(alpha = 0.7f))
                    )
                )
                .border(1.dp, CinemaPrimary.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                color = CinemaTextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}



