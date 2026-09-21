package com.example.ui.tv

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Notifications
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
import androidx.compose.ui.platform.LocalContext
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
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
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TvDetailContent(
    detail: RezkaDetail,
    item: RezkaItem,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    isSubscribed: Boolean = false,
    onToggleSubscription: (() -> Unit)? = null,
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
    onNavigateToMovie: (RezkaItem) -> Unit = {},
    onNavigateToThematic: (String, String) -> Unit = { _, _ -> },
    scrollState: androidx.compose.foundation.lazy.LazyListState? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backButtonFocusRequester = remember { FocusRequester() }
    val trailerButtonFocusRequester = remember { FocusRequester() }
    val favoriteButtonFocusRequester = remember { FocusRequester() }
    val shareButtonFocusRequester = remember { FocusRequester() }
    val mainActionFocusRequester = remember { FocusRequester() }
    val rightScrollState = scrollState ?: rememberLazyListState()
    var isActorsExpanded by remember { mutableStateOf(false) }

    val displayComments = remember(commentsState.comments, detail.comments) {
        if (commentsState.comments.isNotEmpty()) commentsState.comments else detail.comments
    }

    // Автофокус на кнопке "В закладки" и сброс скролла наверх только при смене фильма на ТВ
    var previousDetailId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(detail.id) {
        favoriteButtonFocusRequester.requestFocusSafe()
        if (previousDetailId != null && previousDetailId != detail.id) {
            rightScrollState.scrollToItem(0)
        }
        previousDetailId = detail.id
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        val screenHeight = maxHeight
        val screenWidth = maxWidth
        val isCompactHeight = screenHeight < 520.dp

        val density = androidx.compose.ui.platform.LocalDensity.current
        val coroutineScope = rememberCoroutineScope()
        val scrollStepPx = remember(screenHeight) {
            with(density) { (screenHeight * 0.05f).toPx() }
        }

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
                            up = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                            down = trailerButtonFocusRequester
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                        trailerButtonFocusRequester.requestFocusSafe()
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_UP,
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                        true
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
                            down = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                            up = backButtonFocusRequester
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                        backButtonFocusRequester.requestFocusSafe()
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                        true
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
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = detail.originalTitle,
                                color = CinemaTextGray.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal,
                                letterSpacing = 0.2.sp,
                                lineHeight = 17.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Чипы: Год, Кнопка добавления в закладки
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
                                        up = FocusRequester.Cancel
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.nativeKeyEvent.keyCode) {
                                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                                    backButtonFocusRequester.requestFocusSafe()
                                                    true
                                                }
                                                AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                                    true
                                                }
                                                else -> false
                                            }
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

                            // Кнопка подписки (для сериалов или еще не вышедших фильмов/тайтлов)
                            if ((detail.type == RezkaType.SERIES || !detail.isReleased || isSubscribed) && onToggleSubscription != null) {
                                Surface(
                                    color = if (isSubscribed) CinemaPrimary.copy(alpha = 0.25f) else CinemaCard,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSubscribed) CinemaPrimary else CinemaSecondary.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .tvFocusableItem(
                                            onClick = onToggleSubscription,
                                            scaleFactor = 1.05f,
                                            shape = RoundedCornerShape(6.dp),
                                            lazyListState = rightScrollState
                                        )
                                        .testTag("tv_subscription_button")
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isSubscribed) Icons.Default.NotificationsActive else Icons.Outlined.Notifications,
                                            contentDescription = if (detail.type == RezkaType.SERIES) "Уведомления о новых сериях" else "Уведомление о выходе фильма",
                                            tint = if (isSubscribed) CinemaPrimary else CinemaTextWhite,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Кнопка "Поделиться" (с аргументом текущей озвучки)
                            Surface(
                                color = CinemaCard,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, CinemaSecondary.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .focusProperties {
                                        up = FocusRequester.Cancel
                                    }
                                    .tvFocusableItem(
                                        onClick = {
                                            val shareUrl = com.example.data.RezkaService.buildShareUrl(
                                                itemUrl = item.url,
                                                translatorId = selectedTranslator?.id
                                            )
                                            val shareText = if (detail.title.isNotEmpty()) {
                                                val translatorSuffix = selectedTranslator?.name?.let { " ($it)" } ?: ""
                                                "${detail.title}$translatorSuffix\n$shareUrl"
                                            } else {
                                                shareUrl
                                            }
                                            try {
                                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_SUBJECT, detail.title)
                                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                                }
                                                context.startActivity(Intent.createChooser(sendIntent, "Поделиться фильмом"))
                                            } catch (e: Exception) {
                                                try {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Rezka Link", shareUrl))
                                                    Toast.makeText(context, "Ссылка скопирована в буфер обмена", Toast.LENGTH_SHORT).show()
                                                } catch (ce: Exception) {
                                                    Toast.makeText(context, shareUrl, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        scaleFactor = 1.05f,
                                        shape = RoundedCornerShape(6.dp),
                                        focusRequester = shareButtonFocusRequester,
                                        lazyListState = rightScrollState
                                    )
                                    .testTag("tv_share_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Поделиться",
                                        tint = CinemaTextWhite,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Поделиться",
                                        color = CinemaTextWhite,
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

                            if (detail.directorsList.isNotEmpty()) {
                                TvDetailMetaRowWithLinks(
                                    icon = Icons.Default.MovieFilter,
                                    label = "Режиссёр:",
                                    links = detail.directorsList,
                                    onLinkClick = { link ->
                                        onNavigateToThematic(link.name, link.url)
                                    },
                                    lazyListState = rightScrollState
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
                                TvDetailMetaRowWithLinks(
                                    icon = Icons.Default.CollectionsBookmark,
                                    label = "Из серии:",
                                    links = detail.seriesCollectionList,
                                    onLinkClick = { link ->
                                        onNavigateToThematic(link.name, link.url)
                                    },
                                    lazyListState = rightScrollState
                                )
                            } else if (detail.seriesCollection.isNotEmpty()) {
                                DetailMetaRow(
                                    icon = Icons.Default.CollectionsBookmark,
                                    label = "Из серии:",
                                    value = detail.seriesCollection
                                )
                            }

                            val collectionsToShow = if (detail.collectionsList.isNotEmpty()) {
                                detail.collectionsList
                            } else {
                                detail.inCollections.map { LinkItem(it, "") }
                            }
                            if (collectionsToShow.isNotEmpty()) {
                                TvDetailMetaRowWithLinks(
                                    icon = Icons.Default.FormatListBulleted,
                                    label = "Входит в списки:",
                                    links = collectionsToShow,
                                    onLinkClick = { link ->
                                        onNavigateToThematic(link.name, link.url)
                                    },
                                    lazyListState = rightScrollState
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
                }

                // ---- 3. В главных ролях (Актеры, идентично телефону со ссылками и ТВ-фокусом) ----
                val effectiveActors = if (detail.actorsList.isNotEmpty()) {
                    detail.actorsList
                } else {
                    detail.actors.map { LinkItem(it, "") }
                }
                if (effectiveActors.isNotEmpty()) {
                    item {
                        val initialActorCount = 4
                        val displayActors = if (isActorsExpanded || effectiveActors.size <= initialActorCount) {
                            effectiveActors
                        } else {
                            effectiveActors.take(initialActorCount)
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
                                                backButtonFocusRequester.requestFocusSafe()
                                                true
                                            } else false
                                        }
                                        .tvFocusableItem(
                                            onClick = { isActorsExpanded = !isActorsExpanded },
                                            shape = RoundedCornerShape(6.dp),
                                            lazyListState = rightScrollState
                                        )
                                        .clickable(enabled = effectiveActors.size > initialActorCount) {
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
                                            text = "В главных ролях (${effectiveActors.size})",
                                            color = CinemaTextWhite,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    if (effectiveActors.size > initialActorCount) {
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
                                    displayActors.forEach { actorLink ->
                                        val isClickable = actorLink.url.isNotBlank()
                                        Surface(
                                            color = Color.Transparent,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .then(
                                                    if (isClickable) {
                                                        Modifier.tvFocusableItem(
                                                            onClick = { onNavigateToThematic(actorLink.name, actorLink.url) },
                                                            scaleFactor = 1.02f,
                                                            shape = RoundedCornerShape(6.dp),
                                                            lazyListState = rightScrollState
                                                        )
                                                    } else Modifier
                                                )
                                                .padding(vertical = 2.dp, horizontal = 4.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .background(if (isClickable) CinemaPrimary else CinemaTextGray, CircleShape)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = actorLink.name,
                                                    color = CinemaTextWhite.copy(alpha = 0.95f),
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isClickable) FontWeight.Medium else FontWeight.Normal
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 5. Описание ----
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CinemaDark),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Text(
                                text = "Описание",
                                color = CinemaTextWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = detail.description.ifEmpty { "Описание отсутствует." },
                                color = CinemaTextGray,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                // ---- Франшиза / Сага (Все части) ----
                if (detail.franchiseItems.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = detail.franchiseTitle.ifEmpty { "Все части франшизы" },
                                color = CinemaTextWhite,
                                fontSize = 14.sp,
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
                                        Surface(
                                            color = if (isCurrent) CinemaPrimary.copy(alpha = 0.12f) else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusProperties { left = backButtonFocusRequester }
                                                .onKeyEvent { event ->
                                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                                                        backButtonFocusRequester.requestFocusSafe()
                                                        true
                                                    } else false
                                                }
                                                .tvFocusableItem(
                                                    onClick = {
                                                        if (!isCurrent && franchiseItem.url.isNotEmpty()) {
                                                            val targetItem = RezkaItem(
                                                                id = franchiseItem.id.ifEmpty { franchiseItem.url.hashCode().toString() },
                                                                title = franchiseItem.title,
                                                                subtitle = franchiseItem.year,
                                                                imageUrl = "",
                                                                rating = "",
                                                                url = franchiseItem.url,
                                                                type = detail.type
                                                            )
                                                            onNavigateToMovie(targetItem)
                                                        }
                                                    },
                                                    scaleFactor = 1.02f,
                                                    focusedBorderColor = CinemaPrimary,
                                                    focusedBorderWidth = 2.5.dp,
                                                    shape = RoundedCornerShape(8.dp),
                                                    lazyListState = rightScrollState
                                                )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
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
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (franchiseItem.year.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = franchiseItem.year,
                                                        color = if (isCurrent) CinemaPrimary.copy(alpha = 0.8f) else CinemaTextGray,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
                                                if (!isCurrent && franchiseItem.url.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                        contentDescription = null,
                                                        tint = CinemaTextGray.copy(alpha = 0.5f),
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
                                        backButtonFocusRequester.requestFocusSafe()
                                        true
                                    } else false
                                }
                        )
                    }
                }

                // ---- 7. КНОПКА "СМОТРЕТЬ" ДЛЯ ФИЛЬМОВ ----
                if (detail.type != RezkaType.SERIES) {
                    item {
                        val isPlayEnabled = detail.isReleased
                        Button(
                            onClick = { if (isPlayEnabled) onPlayMovie() },
                            enabled = isPlayEnabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlayEnabled) CinemaPrimary else Color.Gray.copy(alpha = 0.3f),
                                contentColor = if (isPlayEnabled) Color.Black else Color.White.copy(alpha = 0.5f),
                                disabledContainerColor = Color.Gray.copy(alpha = 0.2f),
                                disabledContentColor = Color.White.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .focusProperties {
                                    left = trailerButtonFocusRequester
                                    if (displayComments.isEmpty()) {
                                        down = FocusRequester.Cancel
                                    }
                                }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.nativeKeyEvent.keyCode) {
                                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                                trailerButtonFocusRequester.requestFocusSafe()
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                if (displayComments.isEmpty()) {
                                                    true
                                                } else false
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                .let {
                                    if (isPlayEnabled) {
                                        it.tvFocusableItem(
                                            onClick = onPlayMovie,
                                            scaleFactor = 1.03f,
                                            focusedBorderColor = Color.White,
                                            shape = RoundedCornerShape(10.dp),
                                            focusRequester = mainActionFocusRequester,
                                            lazyListState = rightScrollState
                                        )
                                    } else {
                                        it
                                    }
                                }
                                .testTag("tv_movie_play_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isPlayEnabled) Color.Black else Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isPlayEnabled) "СМОТРЕТЬ" else "ЕЩЕ НЕ ВЫШЕЛ",
                                color = if (isPlayEnabled) Color.Black else Color.White.copy(alpha = 0.5f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // ---- 8. СЕЗОНЫ И СЕРИИ (для сериалов) ----
                if (detail.type == RezkaType.SERIES) {
                    if (!detail.isReleased || effectiveSeasons.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaDark)
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Сериал еще не вышел или нет доступных серий",
                                        color = CinemaTextWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
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
                                        trailerButtonFocusRequester.requestFocusSafe()
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
                                                    trailerButtonFocusRequester.requestFocusSafe()
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
                                             val isLastEpisodeRow = rowIndex == chunks.lastIndex
                                            val hasNoComments = displayComments.isEmpty()
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
                                                        Modifier
                                                            .focusProperties {
                                                                if (colIndex == 0) {
                                                                    left = trailerButtonFocusRequester
                                                                }
                                                                if (isLastEpisodeRow && hasNoComments) {
                                                                    down = FocusRequester.Cancel
                                                                }
                                                            }
                                                            .onKeyEvent { event ->
                                                                if (event.type == KeyEventType.KeyDown) {
                                                                    when (event.nativeKeyEvent.keyCode) {
                                                                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                                                            if (colIndex == 0) {
                                                                                trailerButtonFocusRequester.requestFocusSafe()
                                                                                true
                                                                            } else false
                                                                        }
                                                                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                                            if (isLastEpisodeRow && hasNoComments) {
                                                                                true
                                                                            } else false
                                                                        }
                                                                        else -> false
                                                                    }
                                                                } else false
                                                            }
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
                }

                // ---- 9. ОТЗЫВЫ ЗРИТЕЛЕЙ (ВИРТУАЛИЗИРОВАННЫЙ СПИСОК С ПЛАВНЫМ DPAD СКРОЛЛОМ) ----
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
                        val isLastComment = idx == displayComments.lastIndex
                        Surface(
                            color = CinemaDark,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusProperties {
                                    left = trailerButtonFocusRequester
                                    if (isLastComment) {
                                        down = FocusRequester.Cancel
                                    }
                                }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.nativeKeyEvent.keyCode) {
                                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                                trailerButtonFocusRequester.requestFocusSafe()
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                if (isLastComment) {
                                                    true
                                                } else false
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
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

/**
 * Оптимизированный для пульта ТВ компонент отображения метаданных со ссылками.
 * Каждая ссылка фокусируется пультом с помощью tvFocusableItem и мгновенно
 * переходит по маршруту при нажатии ОК / DPAD_CENTER.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TvDetailMetaRowWithLinks(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    links: List<LinkItem>,
    onLinkClick: (LinkItem) -> Unit,
    lazyListState: androidx.compose.foundation.lazy.LazyListState? = null,
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
            modifier = Modifier.width(110.dp)
        )
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            links.forEach { link ->
                val isClickable = link.url.isNotBlank()
                Surface(
                    color = CinemaDark,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, CinemaBorder),
                    modifier = Modifier
                        .then(
                            if (isClickable) {
                                Modifier.tvFocusableItem(
                                    onClick = { onLinkClick(link) },
                                    scaleFactor = 1.05f,
                                    shape = RoundedCornerShape(6.dp),
                                    lazyListState = lazyListState
                                )
                            } else Modifier
                        )
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
