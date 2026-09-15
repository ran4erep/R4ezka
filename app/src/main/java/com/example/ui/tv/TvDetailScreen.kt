package com.example.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
 * Кинематографический экран фильма для ТВ, повторяющий дизайн и структуру мобильной версии:
 * - Слева: обложка фильма (кинопостер) и строго снизу кнопка трейлера.
 * - Справа: идентично мобильному приложению: название, оригинальное название, бейджи,
 *   единый информационный блок с рейтингами (Кинопоиск, IMDb, HDRezka) и метаданными,
 *   входит в списки, блок актеров, описание, выбор озвучки, сезоны и серии, кнопка "Смотреть" и отзывы.
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
    onAppendNextCommentsPage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val backButtonFocusRequester = remember { FocusRequester() }
    val trailerButtonFocusRequester = remember { FocusRequester() }
    val favoriteButtonFocusRequester = remember { FocusRequester() }
    val mainActionFocusRequester = remember { FocusRequester() }
    val rightScrollState = rememberLazyListState()
    var isActorsExpanded by remember { mutableStateOf(false) }

    // Автофокус на главном действии при входе (кнопка Смотреть / Серия, либо Избранное / Назад)
    LaunchedEffect(Unit) {
        try {
            mainActionFocusRequester.requestFocus()
        } catch (_: Exception) {
            try {
                favoriteButtonFocusRequester.requestFocus()
            } catch (_: Exception) {
                try {
                    backButtonFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        val screenHeight = maxHeight
        val screenWidth = maxWidth
        val isCompactHeight = screenHeight < 520.dp

        // Гарантированное вычисление возрастного рейтинга (из данных RezkaDetail, описания либо жанров)
        val effectiveAgeRestriction = remember(detail.ageRestriction, item.subtitle, detail.description, detail.genres) {
            if (detail.ageRestriction.isNotEmpty()) {
                detail.ageRestriction
            } else {
                val candidates = listOf(item.subtitle, detail.title, detail.description)
                var found = ""
                for (c in candidates) {
                    val match = Regex("""\b(18\+|16\+|12\+|6\+|0\+|PG-13|NC-17|TV-MA|TV-14|TV-PG|R|PG)\b""", RegexOption.IGNORE_CASE).find(c)
                    if (match != null) {
                        found = RezkaService.cleanAgeRestriction(match.value)
                        break
                    }
                }
                if (found.isEmpty()) {
                    when {
                        detail.genres.any { it.contains("ужас", ignoreCase = true) || it.contains("криминал", ignoreCase = true) || it.contains("эротик", ignoreCase = true) } -> "18+"
                        detail.genres.any { it.contains("боевик", ignoreCase = true) || it.contains("триллер", ignoreCase = true) } -> "16+"
                        detail.genres.any { it.contains("мультфильм", ignoreCase = true) || it.contains("детск", ignoreCase = true) || it.contains("семейн", ignoreCase = true) } -> "6+"
                        else -> "16+"
                    }
                } else {
                    found
                }
            }
        }

        // 1. Полноэкранный кинотеатральный фоновый арт (Backdrop) с мягким затемнением
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = detail.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.20f }
            )

            // Градиентная подложка для читаемости текста на ТВ экранах
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                CinemaBlack.copy(alpha = 0.96f),
                                CinemaBlack.copy(alpha = 0.90f),
                                CinemaBlack.copy(alpha = 0.80f)
                            )
                        )
                    )
            )
        }

        // 2. Основная рабочая область: Двухпанельный ТВ-лейаут (Адаптивный под любые размеры экрана)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (isCompactHeight) 16.dp else 24.dp,
                    vertical = if (isCompactHeight) 10.dp else 18.dp
                ),
            horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 16.dp else 24.dp)
        ) {
            // =========================================================================
            // ЛЕВАЯ ПАНЕЛЬ:
            // Адаптивная ширина и высота: постер подстраивается под высоту экрана
            // Кнопка назад -> Обложка (постер) -> Снизу ТОЛЬКО кнопка трейлера.
            // Кнопка трейлера ГАРАНТИРОВАННО помещается на экране даже в телефоне ландшафта!
            // =========================================================================
            val availablePosterHeight = (screenHeight - (if (isCompactHeight) 20.dp else 36.dp) - (if (isCompactHeight) 34.dp else 42.dp) - (if (isCompactHeight) 36.dp else 46.dp) - (if (isCompactHeight) 16.dp else 24.dp)).coerceAtLeast(110.dp)
            val leftPanelWidth = if (isCompactHeight) (availablePosterHeight * 0.68f).coerceIn(135.dp, 260.dp) else 260.dp

            Column(
                modifier = Modifier
                    .width(leftPanelWidth)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 8.dp else 12.dp)
            ) {
                // Кнопка "Назад"
                Surface(
                    color = CinemaCard,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isCompactHeight) 34.dp else 42.dp)
                        .focusProperties {
                            down = trailerButtonFocusRequester
                            right = favoriteButtonFocusRequester
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                        trailerButtonFocusRequester.requestFocus()
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        try {
                                            favoriteButtonFocusRequester.requestFocus()
                                            true
                                        } catch (_: Exception) {
                                            try {
                                                mainActionFocusRequester.requestFocus()
                                                true
                                            } catch (_: Exception) { false }
                                        }
                                    }
                                    else -> false
                                }
                            } else false
                        }
                        .tvFocusableItem(
                            onClick = onBack,
                            scaleFactor = 1.05f,
                            shape = RoundedCornerShape(10.dp),
                            focusRequester = backButtonFocusRequester
                        )
                        .testTag("tv_back_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (isCompactHeight) 10.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = CinemaTextWhite,
                            modifier = Modifier.size(if (isCompactHeight) 16.dp else 18.dp)
                        )
                        Text(
                            text = "Назад к каталогу",
                            color = CinemaTextWhite,
                            fontSize = if (isCompactHeight) 11.sp else 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Постер фильма (пропорции кинопостера 0.68f, адаптивно масштабируемый по высоте)
                Card(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .aspectRatio(0.68f, matchHeightConstraintsFirst = true)
                        .align(Alignment.CenterHorizontally),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = CinemaDark)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = detail.imageUrl,
                            contentDescription = detail.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Бейдж возраста
                        if (effectiveAgeRestriction.isNotEmpty()) {
                            Surface(
                                color = CinemaDark.copy(alpha = 0.88f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(if (isCompactHeight) 6.dp else 8.dp)
                            ) {
                                Text(
                                    text = effectiveAgeRestriction,
                                    color = CinemaPrimary,
                                    fontSize = if (isCompactHeight) 10.sp else 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // СНИЗУ ПОД ОБЛОЖКОЙ: КНОПКА ТРЕЙЛЕРА (ВСЕГДА ВИДНА НА ЭКРАНЕ ЛЮБОГО РАЗМЕРА!)
                Surface(
                    color = CinemaCard,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF0000).copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isCompactHeight) 36.dp else 46.dp)
                        .focusProperties {
                            up = backButtonFocusRequester
                            right = mainActionFocusRequester
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                        backButtonFocusRequester.requestFocus()
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        try {
                                            mainActionFocusRequester.requestFocus()
                                            true
                                        } catch (_: Exception) {
                                            try {
                                                favoriteButtonFocusRequester.requestFocus()
                                                true
                                            } catch (_: Exception) { false }
                                        }
                                    }
                                    else -> false
                                }
                            } else false
                        }
                        .tvFocusableItem(
                            onClick = onLaunchTrailer,
                            scaleFactor = 1.05f,
                            shape = RoundedCornerShape(10.dp),
                            focusRequester = trailerButtonFocusRequester
                        )
                        .testTag("tv_trailer_button")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        YouTubeLogoIcon(
                            width = if (isCompactHeight) 18.dp else 22.dp,
                            height = if (isCompactHeight) 13.dp else 15.dp,
                            playIconSize = if (isCompactHeight) 8.dp else 10.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Трейлер",
                            color = CinemaTextWhite,
                            fontSize = if (isCompactHeight) 12.sp else 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // =========================================================================
            // ПРАВАЯ ПАНЕЛЬ:
            // ИДЕНТИЧНО ТЕЛЕФОННОМУ ИНТЕРФЕЙСУ И ДИЗАЙНУ:
            // 1. Заголовок, оригинальное название и бейджи (Год, Возраст, В закладки)
            // 2. Тот самый блок с информацией (Рейтинги КП/IMDb/HDRezka, Дата, Страна, Режиссёр, Время, Жанры, Из серии)
            // 3. Входит в списки
            // 4. В главных ролях (Актеры)
            // 5. Описание
            // 6. Озвучка / Перевод
            // 7. Кнопка "СМОТРЕТЬ" (для фильмов)
            // 8. Сезоны и Серии (для сериалов) + График выхода серий
            // 9. Отзывы зрителей с пагинацией
            // =========================================================================
            LazyColumn(
                state = rightScrollState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("tv_detail_right_column"),
                contentPadding = PaddingValues(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ---- 1. Заголовок и мета-чипы ----
                item {
                    Column {
                        Text(
                            text = detail.title,
                            color = CinemaTextWhite,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 30.sp
                        )

                        if (detail.originalTitle.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = detail.originalTitle,
                                color = CinemaTextGray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Чипы: Год, Возраст, Кнопка добавления в закладки
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (detail.year.isNotEmpty()) {
                                Surface(
                                    color = CinemaCard,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = detail.year,
                                        color = CinemaTextWhite,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            if (effectiveAgeRestriction.isNotEmpty()) {
                                Surface(
                                    color = CinemaPrimary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = effectiveAgeRestriction,
                                        color = CinemaPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            // Кнопка "В закладки" (доступна прямо в панели действий)
                            Surface(
                                color = CinemaCard,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isFavorite) CinemaPrimary else CinemaSecondary.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier
                                    .focusProperties {
                                        left = backButtonFocusRequester
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                                            backButtonFocusRequester.requestFocus()
                                            true
                                        } else false
                                    }
                                    .tvFocusableItem(
                                        onClick = onToggleFavorite,
                                        scaleFactor = 1.05f,
                                        shape = RoundedCornerShape(6.dp),
                                        focusRequester = favoriteButtonFocusRequester,
                                        lazyListState = rightScrollState
                                    )
                                    .testTag("tv_favorite_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isFavorite) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                                        contentDescription = null,
                                        tint = if (isFavorite) CinemaPrimary else CinemaTextWhite,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = if (isFavorite) "В закладках" else "В закладки",
                                        color = if (isFavorite) CinemaPrimary else CinemaTextWhite,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- 2. Тот самый блок с информацией (ИДЕНТИЧНО телефону) ----
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CinemaDark),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Оценки в рейтингах: IMDb, Кинопоиск, HDRezka
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

                            if (detail.director.isNotEmpty()) {
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

                            if (detail.seriesCollection.isNotEmpty()) {
                                DetailMetaRow(
                                    icon = Icons.Default.CollectionsBookmark,
                                    label = "Из серии:",
                                    value = detail.seriesCollection
                                )
                            }

                            if (effectiveAgeRestriction.isNotEmpty()) {
                                DetailMetaRow(
                                    icon = Icons.Default.Explicit,
                                    label = "Возраст:",
                                    value = effectiveAgeRestriction
                                )
                            }
                        }
                    }
                }

                // ---- 3. Входит в списки (если есть) ----
                if (detail.inCollections.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Входит в списки",
                                color = CinemaTextWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(detail.inCollections) { coll ->
                                    Surface(
                                        color = CinemaCard,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.2f)),
                                        modifier = Modifier
                                            .focusProperties {
                                                left = backButtonFocusRequester
                                            }
                                            .onKeyEvent { event ->
                                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                                                    backButtonFocusRequester.requestFocus()
                                                    true
                                                } else false
                                            }
                                            .tvFocusableItem(
                                                onClick = {},
                                                scaleFactor = 1.05f,
                                                shape = RoundedCornerShape(8.dp),
                                                lazyListState = rightScrollState
                                            )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.EmojiEvents,
                                                contentDescription = null,
                                                tint = CinemaAmber,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = coll,
                                                color = CinemaTextWhite,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 4. В главных ролях (Актеры, идентично телефону) ----
                if (detail.actors.isNotEmpty()) {
                    item {
                        val initialActorCount = 4
                        val displayActors = if (isActorsExpanded || detail.actors.size <= initialActorCount) {
                            detail.actors
                        } else {
                            detail.actors.take(initialActorCount)
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
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
                                        .focusProperties {
                                            left = backButtonFocusRequester
                                        }
                                        .onKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                                                backButtonFocusRequester.requestFocus()
                                                true
                                            } else false
                                        }
                                        .tvFocusableItem(
                                            onClick = { isActorsExpanded = !isActorsExpanded },
                                            shape = RoundedCornerShape(6.dp),
                                            lazyListState = rightScrollState
                                        )
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

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    displayActors.forEach { actor ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .background(CinemaPrimary, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = actor,
                                                color = CinemaTextWhite.copy(alpha = 0.9f),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 5. Описание ----
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                            lineHeight = 20.sp
                        )
                    }
                }

                // ---- 6. Озвучка / Перевод ----
                if (detail.translators.isNotEmpty()) {
                    item {
                        val currentTrans = selectedTranslator ?: detail.translators.first()
                        TvRezkaDropdown(
                            label = "Озвучка",
                            options = detail.translators,
                            selectedOption = currentTrans,
                            onOptionSelected = { trans -> onSelectTranslator(trans) },
                            getLabel = { it.name },
                            modifier = Modifier.fillMaxWidth(if (isCompactHeight) 0.85f else 0.55f),
                            lazyListState = rightScrollState,
                            surfaceModifier = Modifier
                                .focusProperties { left = backButtonFocusRequester }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                                        backButtonFocusRequester.requestFocus()
                                        true
                                    } else false
                                }
                        )
                    }
                }

                // ---- 7. КНОПКА "СМОТРЕТЬ" ДЛЯ ФИЛЬМОВ ----
                if (detail.type != RezkaType.SERIES) {
                    item {
                        Button(
                            onClick = onPlayMovie,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CinemaPrimary,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .focusProperties {
                                    left = trailerButtonFocusRequester
                                }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                                        trailerButtonFocusRequester.requestFocus()
                                        true
                                    } else false
                                }
                                .tvFocusableItem(
                                    onClick = onPlayMovie,
                                    scaleFactor = 1.03f,
                                    shape = RoundedCornerShape(10.dp),
                                    focusRequester = mainActionFocusRequester,
                                    lazyListState = rightScrollState
                                )
                                .testTag("tv_movie_play_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "СМОТРЕТЬ",
                                color = Color.Black,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // ---- 8. СЕЗОНЫ И СЕРИИ (для сериалов) ----
                if (detail.type == RezkaType.SERIES && effectiveSeasons.isNotEmpty()) {
                    // Сезоны (выпадающий список для ТВ)
                    val currentSeason = effectiveSeasons.find { it.id == selectedSeasonId } ?: effectiveSeasons.first()

                    item {
                        TvRezkaDropdown(
                            label = "Сезон",
                            options = effectiveSeasons,
                            selectedOption = currentSeason,
                            onOptionSelected = { s -> onSelectSeason(s.id) },
                            getLabel = { it.name },
                            modifier = Modifier.fillMaxWidth(if (isCompactHeight) 0.85f else 0.55f),
                            lazyListState = rightScrollState,
                            surfaceModifier = Modifier
                                .focusProperties { left = trailerButtonFocusRequester }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                                        trailerButtonFocusRequester.requestFocus()
                                        true
                                    } else false
                                }
                        )
                    }

                    // Серии текущего сезона
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Серии (${currentSeason.episodes.size})",
                                    color = CinemaTextWhite,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // Кнопка расписания онгоинга (как в телефоне рядом с сериями)
                                if (detail.schedule.isNotEmpty()) {
                                    val unreleasedCount = detail.schedule.count { !it.isReleased }
                                    Surface(
                                        color = CinemaCard,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .focusProperties { left = trailerButtonFocusRequester }
                                            .onKeyEvent { event ->
                                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                                                    trailerButtonFocusRequester.requestFocus()
                                                    true
                                                } else false
                                            }
                                            .tvFocusableItem(
                                                onClick = onOpenSchedule,
                                                scaleFactor = 1.05f,
                                                shape = RoundedCornerShape(8.dp),
                                                lazyListState = rightScrollState
                                            )
                                            .testTag("tv_schedule_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CalendarMonth,
                                                contentDescription = null,
                                                tint = CinemaPrimary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Text(
                                                text = "График выхода серий",
                                                color = CinemaTextWhite,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (unreleasedCount > 0) {
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

                            Spacer(modifier = Modifier.height(10.dp))

                            // Сетка серий по 3 штуки в строке
                            val chunks = currentSeason.episodes.chunked(3)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                chunks.forEachIndexed { rowIndex, rowEpisodes ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowEpisodes.forEachIndexed { colIndex, ep ->
                                            val isSelected = selectedEpisodeId == ep.id
                                            val isMainActionTarget = (isSelected || (selectedEpisodeId.isNullOrEmpty() && rowIndex == 0 && colIndex == 0))
                                            Surface(
                                                color = if (isSelected) CinemaPrimary.copy(alpha = 0.2f) else CinemaDark,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (isSelected) CinemaPrimary else Color.White.copy(alpha = 0.12f)
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .then(
                                                        if (colIndex == 0) {
                                                            Modifier
                                                                .focusProperties { left = trailerButtonFocusRequester }
                                                                .onKeyEvent { event ->
                                                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                                                                        trailerButtonFocusRequester.requestFocus()
                                                                        true
                                                                    } else false
                                                                }
                                                        } else Modifier
                                                    )
                                                    .tvFocusableItem(
                                                        onClick = {
                                                            onSelectEpisode(ep.id)
                                                            onPlayEpisode(currentSeason, ep)
                                                        },
                                                        scaleFactor = 1.04f,
                                                        shape = RoundedCornerShape(8.dp),
                                                        focusRequester = if (isMainActionTarget) mainActionFocusRequester else null,
                                                        lazyListState = rightScrollState
                                                    )
                                                    .testTag("tv_episode_${ep.id}")
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 10.dp),
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
                                                        Spacer(modifier = Modifier.width(6.dp))
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

                // ---- 9. ОТЗЫВЫ ЗРИТЕЛЕЙ (ВИРТУАЛИЗИРОВАННЫЙ СПИСОК С ПЛАВНЫМ DPAD СКРОЛЛОМ) ----
                val displayComments = if (commentsState.comments.isNotEmpty()) commentsState.comments else detail.comments
                val totalPages = maxOf(commentsState.totalPages, detail.commentsTotalPages, commentsState.currentPage)
                val totalReviewsCount = when {
                    commentsState.totalCount > 0 -> commentsState.totalCount
                    detail.commentsTotalCount > 0 -> detail.commentsTotalCount
                    else -> displayComments.size
                }

                item(key = "comments_header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.RateReview,
                                contentDescription = null,
                                tint = CinemaPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (totalReviewsCount > 0) "Отзывы зрителей ($totalReviewsCount)" else "Отзывы зрителей",
                                color = CinemaTextWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (totalPages > 1) {
                            Text(
                                text = "Стр. ${commentsState.currentPage} из $totalPages",
                                color = CinemaTextGray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                if (commentsState.isLoading) {
                    item(key = "comments_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (displayComments.isEmpty()) {
                    item(key = "comments_empty") {
                        Text(
                            text = "Отзывов пока нет.",
                            color = CinemaTextGray,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    itemsIndexed(
                        items = displayComments,
                        key = { idx, comment -> "${comment.id}_${comment.author}_${comment.date}_$idx" }
                    ) { idx, comment ->
                        Surface(
                            color = CinemaDark,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusableItem(
                                    onClick = {},
                                    scaleFactor = 1.01f,
                                    shape = RoundedCornerShape(12.dp),
                                    focusedBorderColor = CinemaPrimary.copy(alpha = 0.5f),
                                    lazyListState = rightScrollState
                                )
                                .testTag("tv_comment_card_$idx")
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    CommentUserAvatar(
                                        avatarUrl = comment.avatarUrl,
                                        authorName = comment.author,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = comment.author,
                                            color = CinemaTextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        if (comment.date.isNotEmpty()) {
                                            Text(
                                                text = comment.date,
                                                color = CinemaTextGray,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    // Бейдж лайков отзыва
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

                                Spacer(modifier = Modifier.height(8.dp))

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

                // Пагинация отзывов (Загрузить ещё + Номера страниц с поддержкой ТВ-пульта)
                if (totalPages > 1) {
                    item(key = "comments_pagination") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Кнопка "Загрузить ещё отзывы"
                            if (commentsState.hasMore || commentsState.currentPage < totalPages) {
                                Surface(
                                    color = CinemaCard,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .tvFocusableItem(
                                            onClick = onAppendNextCommentsPage,
                                            scaleFactor = 1.03f,
                                            shape = RoundedCornerShape(10.dp),
                                            lazyListState = rightScrollState
                                        )
                                        .testTag("tv_comments_load_more_button")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        if (commentsState.isLoadingMore) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = CinemaPrimary,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Загрузка...", fontSize = 12.sp, color = CinemaTextWhite)
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
                                                fontSize = 12.sp,
                                                color = CinemaTextWhite,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }

                            // Постраничная панель [←] [1] [2] [3]... [→]
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = CinemaDark),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    // Назад
                                    Surface(
                                        color = CinemaCard,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .tvFocusableItem(
                                                onClick = {
                                                    if (commentsState.currentPage > 1) {
                                                        onLoadCommentsPage(commentsState.currentPage - 1)
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                lazyListState = rightScrollState
                                            )
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Предыдущая страница",
                                            tint = if (commentsState.currentPage > 1) CinemaTextWhite else CinemaTextGray.copy(alpha = 0.3f),
                                            modifier = Modifier.padding(8.dp).size(18.dp)
                                        )
                                    }

                                    // Номера страниц
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        items((1..totalPages).toList()) { pageNum ->
                                            val isSelected = pageNum == commentsState.currentPage
                                            Surface(
                                                color = if (isSelected) CinemaPrimary else CinemaCard,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (isSelected) CinemaPrimary else CinemaSecondary.copy(alpha = 0.3f)
                                                ),
                                                modifier = Modifier.tvFocusableItem(
                                                    onClick = { onLoadCommentsPage(pageNum) },
                                                    shape = RoundedCornerShape(8.dp),
                                                    lazyListState = rightScrollState
                                                )
                                            ) {
                                                Text(
                                                    text = pageNum.toString(),
                                                    color = if (isSelected) CinemaTextWhite else CinemaTextGray,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Вперёд
                                    Surface(
                                        color = CinemaCard,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.tvFocusableItem(
                                            onClick = {
                                                if (commentsState.currentPage < totalPages) {
                                                    onLoadCommentsPage(commentsState.currentPage + 1)
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            lazyListState = rightScrollState
                                        )
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Следующая страница",
                                            tint = if (commentsState.currentPage < totalPages) CinemaTextWhite else CinemaTextGray.copy(alpha = 0.3f),
                                            modifier = Modifier.padding(8.dp).size(18.dp)
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
}
