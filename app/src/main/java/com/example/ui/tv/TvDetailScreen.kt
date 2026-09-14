package com.example.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.*
import com.example.ui.MovieCommentsState
import com.example.ui.screens.CommentUserAvatar
import com.example.ui.screens.DetailMetaRow
import com.example.ui.screens.DetailRatingsRow
import com.example.ui.screens.YouTubeLogoIcon
import com.example.ui.theme.*

/**
 * Кинематографический двухпанельный интерфейс страницы фильма для телевизоров и пульта D-Pad.
 * Полная поддержка навигации стрелками пульта, автофокус на кнопке "Смотреть"
 * и плавный скроллинг с пульта через dpadScrollable.
 */
@Composable
fun TvDetailContent(
    detail: RezkaDetail,
    item: RezkaItem,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    selectedTranslator: Translator?,
    onSelectTranslator: (Translator) -> Unit,
    selectedSeasonId: Int?,
    onSelectSeason: (Int) -> Unit,
    selectedEpisodeId: String?,
    onSelectEpisode: (String) -> Unit,
    effectiveSeasons: List<Season>,
    commentsState: MovieCommentsState,
    onLoadCommentsPage: (Int) -> Unit,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (Season, Episode) -> Unit,
    onLaunchTrailer: () -> Unit,
    onOpenSchedule: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playButtonFocusRequester = remember { FocusRequester() }
    val rightScrollState = rememberLazyListState()

    // Автофокус на главной кнопке просмотра фильма при открытии на ТВ
    LaunchedEffect(Unit) {
        try {
            playButtonFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        // 1. Полноэкранный кинотеатральный фоновый арт (Backdrop) с мягким затемнением
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = detail.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.22f }
            )

            // Градиентная подложка для читаемости на любых телевизорах
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                CinemaBlack.copy(alpha = 0.96f),
                                CinemaBlack.copy(alpha = 0.88f),
                                CinemaBlack.copy(alpha = 0.75f)
                            )
                        )
                    )
            )
        }

        // 2. Основная рабочая область: Две панели
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            // === ЛЕВАЯ ПАНЕЛЬ: Постер, кнопки действий с пульта и метаданные ===
            Column(
                modifier = Modifier
                    .width(290.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Кнопка "Назад"
                Surface(
                    color = CinemaCard,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .tvFocusableItem(
                            onClick = onBack,
                            scaleFactor = 1.05f,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .testTag("tv_back_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = CinemaTextWhite,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Назад к каталогу",
                            color = CinemaTextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Постер фильма
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = CinemaDark)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = detail.imageUrl,
                            contentDescription = detail.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Бейджи качества и возраста
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (detail.ageRestriction.isNotEmpty()) {
                                Surface(
                                    color = CinemaDark.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = detail.ageRestriction,
                                        color = CinemaPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Плашка рейтингов (Кинопоиск, IMDb, HDRezka)
                DetailRatingsRow(
                    ratingInfo = detail.ratingInfo,
                    fallbackRating = detail.rating
                )

                // КНОПКА "СМОТРЕТЬ" (Главный фокус)
                val isSeries = detail.type == RezkaType.SERIES
                Surface(
                    color = CinemaPrimary,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .tvFocusableItem(
                            onClick = {
                                if (isSeries) {
                                    val currentSeason = effectiveSeasons.find { it.id == selectedSeasonId } ?: effectiveSeasons.firstOrNull()
                                    if (currentSeason != null) {
                                        val ep = currentSeason.episodes.find { it.id == selectedEpisodeId } ?: currentSeason.episodes.firstOrNull()
                                        if (ep != null) {
                                            onPlayEpisode(currentSeason, ep)
                                        }
                                    }
                                } else {
                                    onPlayMovie()
                                }
                            },
                            scaleFactor = 1.05f,
                            shape = RoundedCornerShape(12.dp),
                            focusRequester = playButtonFocusRequester
                        )
                        .testTag("tv_play_primary_button")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSeries) "СМОТРЕТЬ СЕРИЮ" else "СМОТРЕТЬ ФИЛЬМ",
                            color = Color.Black,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Кнопки "Трейлер" и "В закладки" в ряд
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Трейлер
                    Surface(
                        color = CinemaCard,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFFF0000).copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .tvFocusableItem(
                                onClick = onLaunchTrailer,
                                scaleFactor = 1.05f,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .testTag("tv_trailer_button")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            YouTubeLogoIcon(width = 18.dp, height = 13.dp, playIconSize = 9.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Трейлер",
                                color = CinemaTextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // В закладки
                    Surface(
                        color = CinemaCard,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isFavorite) CinemaPrimary else Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .tvFocusableItem(
                                onClick = onToggleFavorite,
                                scaleFactor = 1.05f,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .testTag("tv_favorite_button")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = null,
                                tint = if (isFavorite) CinemaPrimary else CinemaTextWhite,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isFavorite) "В закладках" else "В закладки",
                                color = if (isFavorite) CinemaPrimary else CinemaTextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Расписание онгоинга (если есть)
                if (detail.schedule.isNotEmpty()) {
                    Surface(
                        color = CinemaCard,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .tvFocusableItem(
                                onClick = onOpenSchedule,
                                scaleFactor = 1.05f,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .testTag("tv_schedule_button")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = CinemaPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "График выхода серий",
                                color = CinemaTextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Краткие метаданные
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CinemaDark.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val displayDate = detail.releaseDate.ifEmpty { detail.year }
                        if (displayDate.isNotEmpty()) {
                            DetailMetaRow(Icons.Default.DateRange, "Дата:", displayDate)
                        }
                        val displayCountry = detail.countryFlag.ifEmpty { detail.country }
                        if (displayCountry.isNotEmpty()) {
                            DetailMetaRow(Icons.Default.Public, "Страна:", displayCountry)
                        }
                        if (detail.director.isNotEmpty()) {
                            DetailMetaRow(Icons.Default.MovieFilter, "Режиссёр:", detail.director)
                        }
                        if (detail.duration.isNotEmpty()) {
                            DetailMetaRow(Icons.Default.Schedule, "Время:", detail.duration)
                        }
                    }
                }
            }

            // === ПРАВАЯ ПАНЕЛЬ: Интерактивный прокручиваемый контент с пульта ===
            LazyColumn(
                state = rightScrollState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .dpadScrollable(rightScrollState)
                    .testTag("tv_detail_right_column"),
                contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // 1. Заголовок и оригинальное название
                item {
                    Column {
                        Text(
                            text = detail.title,
                            color = CinemaTextWhite,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 34.sp
                        )
                        if (detail.originalTitle.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = detail.originalTitle,
                                color = CinemaTextGray,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Жанры
                        if (detail.genres.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                detail.genres.forEach { genre ->
                                    Surface(
                                        color = CinemaCard,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = genre,
                                            color = CinemaTextWhite.copy(alpha = 0.85f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Описание фильма
                item {
                    Column {
                        Text(
                            text = "О ФИЛЬМЕ",
                            color = CinemaPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = detail.description.ifEmpty { "Описание отсутствует." },
                            color = CinemaTextWhite.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            lineHeight = 22.sp
                        )
                    }
                }

                // 3. Выбор озвучки / перевода (горизонтальный ряд с фокусом пульта)
                if (detail.translators.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                text = "ОЗВУЧКА / ПЕРЕВОД",
                                color = CinemaPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val currentTrans = selectedTranslator ?: detail.translators.first()

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(detail.translators) { trans ->
                                    val isSelected = trans.id == currentTrans.id
                                    Surface(
                                        color = if (isSelected) CinemaPrimary.copy(alpha = 0.25f) else CinemaDark,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) CinemaPrimary else Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier
                                            .tvFocusableItem(
                                                onClick = { onSelectTranslator(trans) },
                                                scaleFactor = 1.06f,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .testTag("tv_translator_${trans.id}")
                                    ) {
                                        Text(
                                            text = trans.name,
                                            color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Сезоны и серии (для сериалов)
                if (detail.type == RezkaType.SERIES && effectiveSeasons.isNotEmpty()) {
                    // Ряд сезонов
                    item {
                        Column {
                            Text(
                                text = "СЕЗОНЫ",
                                color = CinemaPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(effectiveSeasons) { s ->
                                    val isSelected = selectedSeasonId == s.id
                                    Surface(
                                        color = if (isSelected) CinemaPrimary.copy(alpha = 0.25f) else CinemaDark,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) CinemaPrimary else Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier
                                            .tvFocusableItem(
                                                onClick = { onSelectSeason(s.id) },
                                                scaleFactor = 1.06f,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .testTag("tv_season_${s.id}")
                                    ) {
                                        Text(
                                            text = s.name,
                                            color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Сетка серий текущего сезона
                    val currentSeason = effectiveSeasons.find { it.id == selectedSeasonId } ?: effectiveSeasons.first()
                    item {
                        Column {
                            Text(
                                text = "СЕРИИ (${currentSeason.episodes.size})",
                                color = CinemaPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            val chunks = currentSeason.episodes.chunked(3)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                chunks.forEach { rowEpisodes ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowEpisodes.forEach { ep ->
                                            val isSelected = selectedEpisodeId == ep.id
                                            Surface(
                                                color = if (isSelected) CinemaPrimary.copy(alpha = 0.2f) else CinemaDark,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (isSelected) CinemaPrimary else Color.White.copy(alpha = 0.15f)
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .tvFocusableItem(
                                                        onClick = {
                                                            onSelectEpisode(ep.id)
                                                            onPlayEpisode(currentSeason, ep)
                                                        },
                                                        scaleFactor = 1.05f,
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .testTag("tv_episode_${ep.id}")
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 12.dp),
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
                                        if (rowEpisodes.size < 3) {
                                            repeat(3 - rowEpisodes.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. В главных ролях (Актеры)
                if (detail.actors.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                text = "В ГЛАВНЫХ РОЛЯХ",
                                color = CinemaPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(detail.actors) { actor ->
                                    Surface(
                                        color = CinemaCard,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                                    ) {
                                        Text(
                                            text = actor,
                                            color = CinemaTextWhite,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. Отзывы зрителей
                item {
                    val displayComments = if (commentsState.comments.isNotEmpty()) commentsState.comments else detail.comments
                    val totalPages = maxOf(commentsState.totalPages, detail.commentsTotalPages, commentsState.currentPage)
                    val totalReviewsCount = when {
                        commentsState.totalCount > 0 -> commentsState.totalCount
                        detail.commentsTotalCount > 0 -> detail.commentsTotalCount
                        else -> displayComments.size
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (totalReviewsCount > 0) "ОТЗЫВЫ ЗРИТЕЛЕЙ ($totalReviewsCount)" else "ОТЗЫВЫ ЗРИТЕЛЕЙ",
                                color = CinemaPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            if (totalPages > 1) {
                                Text(
                                    text = "Стр. ${commentsState.currentPage} из $totalPages",
                                    color = CinemaTextGray,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        if (commentsState.isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(24.dp))
                            }
                        } else if (displayComments.isEmpty()) {
                            Text(
                                text = "Отзывов пока нет.",
                                color = CinemaTextGray,
                                fontSize = 13.sp
                            )
                        } else {
                            displayComments.take(6).forEach { comment ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = CinemaDark.copy(alpha = 0.8f)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            CommentUserAvatar(
                                                avatarUrl = comment.avatarUrl,
                                                authorName = comment.author,
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = comment.author,
                                                color = CinemaTextWhite,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = comment.date,
                                                color = CinemaTextGray,
                                                fontSize = 10.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = comment.text,
                                            color = CinemaTextWhite.copy(alpha = 0.9f),
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }

                            // Пагинация отзывов с пульта
                            if (totalPages > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (commentsState.currentPage > 1) {
                                        Surface(
                                            color = CinemaCard,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .tvFocusableItem(
                                                    onClick = { onLoadCommentsPage(commentsState.currentPage - 1) },
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text("← Назад", color = CinemaTextWhite, fontSize = 12.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    if (commentsState.currentPage < totalPages) {
                                        Surface(
                                            color = CinemaCard,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .tvFocusableItem(
                                                    onClick = { onLoadCommentsPage(commentsState.currentPage + 1) },
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text("Вперёд →", color = CinemaTextWhite, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
