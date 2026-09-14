package com.example.ui.tv

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.*
import com.example.ui.CatalogState
import com.example.ui.RezkaViewModel
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.*

enum class TvNavDestination(val title: String, val icon: ImageVector) {
    CATALOG("Каталог", Icons.Default.Movie),
    FAVORITES("Избранное", Icons.Default.Bookmark),
    HISTORY("История", Icons.Default.History),
    SETTINGS("Настройки", Icons.Default.Settings)
}

/**
 * Специализированный 10-foot интерфейс для Android TV и Smart TV с управлением с пульта.
 * Особенности:
 * 1. Интеллектуальный боковой сайдбар с автораскрытием при фокусе с пульта.
 * 2. Динамический Hero Preview выбранного элемента при навигации стрелками D-Pad.
 * 3. Адаптивная TV-сетка постеров с крупными превью, GPU-масштабированием и неоновой подсветкой.
 * 4. Быстрое переключение категорий (Фильмы, Сериалы, Аниме, Мультфильмы) и разделов.
 */
@Composable
fun TvMainScreen(
    viewModel: RezkaViewModel,
    onNavigateToDetail: (RezkaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedDestination by remember { mutableStateOf(TvNavDestination.CATALOG) }
    var isSidebarFocused by remember { mutableStateOf(false) }

    val catalogState by viewModel.catalogState.collectAsState()
    val currentType by viewModel.currentType.collectAsState()
    val currentSection by viewModel.currentSection.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val isEndReached by viewModel.isEndReached.collectAsState()

    // Элемент, на котором сейчас находится фокус пульта (для Hero Preview)
    var focusedItem by remember { mutableStateOf<RezkaItem?>(null) }

    // Анимированная ширина бокового меню: 64dp в свернутом виде, 190dp при фокусе
    val sidebarWidth by animateDpAsState(
        targetValue = if (isSidebarFocused) 190.dp else 64.dp,
        animationSpec = tween(durationMillis = 200),
        label = "tv_sidebar_width"
    )

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        // ---- 1. TV SIDEBAR (NAVIGATION RAIL) ----
        Column(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxHeight()
                .background(CinemaDark)
                .padding(vertical = 16.dp, horizontal = 8.dp)
                .onFocusChanged { isSidebarFocused = it.hasFocus },
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // TV Brand Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(CinemaPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (isSidebarFocused) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "R4EZKA TV",
                            color = CinemaTextWhite,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Navigation Destinations
                TvNavDestination.values().forEach { dest ->
                    val isSelected = selectedDestination == dest
                    TvSidebarButton(
                        destination = dest,
                        isSelected = isSelected,
                        isExpanded = isSidebarFocused,
                        onClick = { selectedDestination = dest }
                    )
                }
            }

            // Bottom Device Status Badge
            if (isSidebarFocused) {
                Surface(
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CastConnected,
                            contentDescription = null,
                            tint = CinemaPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Android TV Mode",
                            color = CinemaTextGray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // ---- 2. MAIN CONTENT AREA ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            when (selectedDestination) {
                TvNavDestination.CATALOG -> {
                    TvCatalogContent(
                        viewModel = viewModel,
                        catalogState = catalogState,
                        currentType = currentType,
                        currentSection = currentSection,
                        isLoadingMore = isLoadingMore,
                        isEndReached = isEndReached,
                        focusedItem = focusedItem,
                        onItemFocused = { focusedItem = it },
                        onNavigateToDetail = onNavigateToDetail
                    )
                }
                TvNavDestination.FAVORITES -> {
                    FavoritesScreen(
                        viewModel = viewModel,
                        onNavigateToDetail = onNavigateToDetail
                    )
                }
                TvNavDestination.HISTORY -> {
                    HistoryScreen(
                        viewModel = viewModel,
                        onNavigateToDetail = onNavigateToDetail
                    )
                }
                TvNavDestination.SETTINGS -> {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = null
                    )
                }
            }
        }
    }
}

/**
 * Кнопка бокового меню для ТВ с фокусом от пульта.
 */
@Composable
private fun TvSidebarButton(
    destination: TvNavDestination,
    isSelected: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) CinemaPrimary.copy(alpha = 0.2f) else Color.Transparent
    val contentColor = if (isSelected) CinemaPrimary else CinemaTextWhite

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .tvFocusableItem(
                onClick = onClick,
                scaleFactor = 1.04f,
                focusedBorderWidth = 2.dp,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.title,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        if (isExpanded) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = destination.title,
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Контент каталога ТВ:
 * - Вверху Hero Preview выбранного с пульта фильма
 * - Полоса категорий (Фильмы, Сериалы, Аниме, Мультфильмы)
 * - Сетка карточек с фокусом пульта
 */
@Composable
private fun TvCatalogContent(
    viewModel: RezkaViewModel,
    catalogState: CatalogState,
    currentType: RezkaType,
    currentSection: SectionType,
    isLoadingMore: Boolean,
    isEndReached: Boolean,
    focusedItem: RezkaItem?,
    onItemFocused: (RezkaItem) -> Unit,
    onNavigateToDetail: (RezkaItem) -> Unit
) {
    val gridState = rememberLazyGridState()

    // Пагинация для ТВ-сетки
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItems = gridState.layoutInfo.totalItemsCount
            val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleIndex >= totalItems - 6
        }
    }

    LaunchedEffect(shouldLoadMore, isLoadingMore, isEndReached) {
        if (shouldLoadMore && !isLoadingMore && !isEndReached && viewModel.searchQuery.isEmpty()) {
            viewModel.loadNextPage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp, start = 16.dp, end = 16.dp)
    ) {
        // ---- 1. HERO PREVIEW SECTION ----
        val previewItem = focusedItem ?: (catalogState as? CatalogState.Success)?.items?.firstOrNull()
        TvHeroPreview(item = previewItem, onPlayClick = {
            previewItem?.let { onNavigateToDetail(it) }
        })

        Spacer(modifier = Modifier.height(10.dp))

        // ---- 2. TV CATEGORY TABS ROW ----
        TvCategoryTabsRow(
            currentType = currentType,
            currentSection = currentSection,
            onTypeSelected = { type ->
                viewModel.loadCatalog(type = type, genre = "", forceRefresh = true)
            },
            onSectionSelected = { section ->
                viewModel.loadCatalog(section = section, forceRefresh = true)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ---- 3. TV MOVIES GRID ----
        when (catalogState) {
            is CatalogState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(48.dp))
                }
            }
            is CatalogState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(catalogState.message, color = CinemaTextWhite, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.loadCatalog(forceRefresh = true) },
                        colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary)
                    ) {
                        Text("Повторить")
                    }
                }
            }
            is CatalogState.Success -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    state = gridState,
                    contentPadding = PaddingValues(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("tv_catalog_grid")
                ) {
                    items(
                        items = catalogState.items,
                        key = { it.id }
                    ) { item ->
                        TvMovieCard(
                            item = item,
                            onClick = { onNavigateToDetail(item) },
                            onFocused = { onItemFocused(item) }
                        )
                    }

                    if (isLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Динамический Hero баннер для ТВ:
 * Отображает постер, название, рейтинг и краткую информацию о фильме, на котором находится фокус пульта.
 */
@Composable
private fun TvHeroPreview(
    item: RezkaItem?,
    onPlayClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CinemaDark)
    ) {
        if (item != null) {
            // Фоновoe изображение с градиентом
            Row(modifier = Modifier.fillMaxSize()) {
                // Левая колонка: информация о фильме
                Column(
                    modifier = Modifier
                        .weight(1.3f)
                        .fillMaxHeight()
                        .padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        // Бейдж рейтинга и тип
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (item.rating.isNotEmpty()) {
                                Surface(
                                    color = CinemaPrimary,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = item.rating,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = item.subtitle,
                                color = CinemaTextGray,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = item.title,
                            color = CinemaTextWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Нажмите 'ОК' на пульте для просмотра подробностей, выбора озвучки или серии",
                            color = CinemaMuted,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Кнопка быстрого запуска
                    Row(
                        modifier = Modifier
                            .tvFocusableItem(
                                onClick = onPlayClick,
                                scaleFactor = 1.05f,
                                focusedBorderWidth = 2.dp,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(CinemaPrimary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Смотреть",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Правая колонка: красивое превью постера с мягким градиентом
                Box(
                    modifier = Modifier
                        .weight(0.7f)
                        .fillMaxHeight()
                ) {
                    AsyncImage(
                        model = item.imageUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Градиентное затемнение слева для плавного перехода в текст
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(CinemaDark, Color.Transparent),
                                    startX = 0f,
                                    endX = 140f
                                )
                            )
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Выберите фильм из каталога", color = CinemaTextGray, fontSize = 14.sp)
            }
        }
    }
}

/**
 * Строка переключения категорий и разделов, адаптированная под D-Pad стрелки.
 */
@Composable
private fun TvCategoryTabsRow(
    currentType: RezkaType,
    currentSection: SectionType,
    onTypeSelected: (RezkaType) -> Unit,
    onSectionSelected: (SectionType) -> Unit
) {
    val categories = remember {
        listOf(
            RezkaType.MOVIE to "Фильмы",
            RezkaType.SERIES to "Сериалы",
            RezkaType.ANIME to "Аниме",
            RezkaType.CARTOON to "Мультфильмы"
        )
    }

    val sections = remember {
        listOf(
            SectionType.LATEST to "Новинки",
            SectionType.POPULAR to "Популярные",
            SectionType.WATCHING to "Смотрят сейчас"
        )
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(categories) { (type, title) ->
            val isSelected = currentType == type
            val bg = if (isSelected) CinemaPrimary else CinemaDark
            val fg = if (isSelected) Color.White else CinemaTextWhite

            Box(
                modifier = Modifier
                    .tvFocusableItem(
                        onClick = { onTypeSelected(type) },
                        scaleFactor = 1.05f,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = title,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }

        item {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(26.dp)
                    .background(CinemaMuted.copy(alpha = 0.4f))
            )
        }

        items(sections) { (section, title) ->
            val isSelected = currentSection == section
            val bg = if (isSelected) CinemaSecondary else CinemaDark
            val fg = if (isSelected) Color.White else CinemaTextGray

            Box(
                modifier = Modifier
                    .tvFocusableItem(
                        onClick = { onSectionSelected(section) },
                        scaleFactor = 1.05f,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    text = title,
                    color = fg,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * Карточка фильма для ТВ.
 * Поддерживает фокус с пульта, плавное GPU-увеличение, поднятие zIndex и обновление Hero Preview.
 */
@Composable
private fun TvMovieCard(
    item: RezkaItem,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusableItem(
                onClick = onClick,
                onFocused = onFocused,
                scaleFactor = 1.08f,
                focusedBorderWidth = 3.dp,
                shape = RoundedCornerShape(12.dp)
            )
            .testTag("tv_movie_card_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = CinemaDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f)
            ) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (item.rating.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(CinemaPrimary, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = item.rating,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text(
                    text = item.title,
                    color = CinemaTextWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    color = CinemaTextGray,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
