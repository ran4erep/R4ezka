package com.example.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.example.data.*
import com.example.ui.CatalogState
import com.example.ui.RezkaViewModel
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.components.AuthDialog
import com.example.ui.components.UserAvatar
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
    isTopScreen: Boolean = true,
    modifier: Modifier = Modifier
) {
    var selectedDestination by remember { mutableStateOf(TvNavDestination.CATALOG) }
    var isSidebarFocused by remember { mutableStateOf(false) }

    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentUserAvatar by viewModel.currentUserAvatar.collectAsState()
    var showAuthDialog by remember { mutableStateOf(false) }

    if (showAuthDialog) {
        AuthDialog(
            viewModel = viewModel,
            onDismiss = { showAuthDialog = false }
        )
    }

    val catalogState by viewModel.catalogState.collectAsState()
    val currentType by viewModel.currentType.collectAsState()
    val currentSection by viewModel.currentSection.collectAsState()
    val currentGenre by viewModel.currentGenre.collectAsState()
    val genresList by viewModel.genresList.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val isEndReached by viewModel.isEndReached.collectAsState()
    val cardGridMode by viewModel.cardGridMode.collectAsState()
    val parsedCardGrid = remember(cardGridMode) { RezkaService.parseCardGrid(cardGridMode) }

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
        val sidebarCatalogFocusRequester = remember { FocusRequester() }
        val rightContentFocusRequester = remember { FocusRequester() }

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
                // Account / Login Button
                TvAccountButton(
                    isLoggedIn = isLoggedIn,
                    currentUser = currentUser,
                    currentUserAvatar = currentUserAvatar,
                    isExpanded = isSidebarFocused,
                    onClick = { showAuthDialog = true }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Navigation Destinations
                TvNavDestination.values().forEach { dest ->
                    val isSelected = selectedDestination == dest
                    TvSidebarButton(
                        destination = dest,
                        isSelected = isSelected,
                        isExpanded = isSidebarFocused,
                        focusRequester = if (dest == TvNavDestination.CATALOG) sidebarCatalogFocusRequester else null,
                        onRight = if (dest == TvNavDestination.CATALOG) {
                            { rightContentFocusRequester.requestFocusSafe() }
                        } else null,
                        onClick = { selectedDestination = dest }
                    )
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
                        currentGenre = currentGenre,
                        genresList = genresList,
                        isLoadingMore = isLoadingMore,
                        isEndReached = isEndReached,
                        onNavigateToDetail = onNavigateToDetail,
                        sidebarFocusRequester = sidebarCatalogFocusRequester,
                        entryFocusRequester = rightContentFocusRequester,
                        isTopScreen = isTopScreen
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
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onRight: (() -> Unit)? = null
) {
    val bgColor = if (isSelected) CinemaPrimary.copy(alpha = 0.2f) else Color.Transparent
    val contentColor = if (isSelected) CinemaPrimary else CinemaTextWhite

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (onRight != null) {
                        onRight()
                        true
                    } else false
                } else false
            }
            .tvFocusableItem(
                onClick = onClick,
                scaleFactor = 1.04f,
                focusedBorderWidth = 2.dp,
                shape = RoundedCornerShape(10.dp),
                focusRequester = focusRequester
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
 * Кнопка профиля / входа для ТВ бокового меню.
 * Поддерживает управление с пульта (D-Pad), отображает аватар пользователя,
 * логин или кнопку входа в аккаунт с переходом в диалог профиля.
 */
@Composable
private fun TvAccountButton(
    isLoggedIn: Boolean,
    currentUser: String?,
    currentUserAvatar: String?,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isLoggedIn) CinemaCard.copy(alpha = 0.5f) else Color.Transparent)
            .tvFocusableItem(
                onClick = onClick,
                scaleFactor = 1.04f,
                focusedBorderWidth = 2.dp,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isLoggedIn) {
                UserAvatar(
                    avatar = currentUserAvatar,
                    size = 32.dp
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(CinemaPrimary.copy(alpha = 0.18f))
                        .border(1.dp, CinemaPrimary.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Войти в аккаунт",
                        tint = CinemaPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        if (isExpanded) {
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isLoggedIn) (currentUser ?: "Профиль") else "Войти",
                    color = CinemaTextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isLoggedIn) "Аккаунт" else "Синхронизация",
                    color = if (isLoggedIn) CinemaPrimary else CinemaTextGray,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
    currentGenre: String,
    genresList: List<GenreItem>,
    isLoadingMore: Boolean,
    isEndReached: Boolean,
    onNavigateToDetail: (RezkaItem) -> Unit,
    sidebarFocusRequester: FocusRequester,
    entryFocusRequester: FocusRequester,
    isTopScreen: Boolean = true
) {
    val gridState = rememberLazyGridState()
    val searchBarFocusRequester = entryFocusRequester
    val categoryDropdownFocusRequester = remember { FocusRequester() }
    val sectionDropdownFocusRequester = remember { FocusRequester() }
    val genreDropdownFocusRequester = remember { FocusRequester() }

    val coroutineScope = rememberCoroutineScope()
    val cardGridMode by viewModel.cardGridMode.collectAsState()
    val parsedCardGrid = remember(cardGridMode) { RezkaService.parseCardGrid(cardGridMode) }
    var lastFocusedIndex by remember { mutableStateOf(0) }
    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun getFocusRequesterForIndex(index: Int): FocusRequester {
        return itemFocusRequesters.getOrPut(index) { FocusRequester() }
    }

    val columnCount by remember(parsedCardGrid) {
        derivedStateOf {
            if (parsedCardGrid != null) {
                parsedCardGrid.columns
            } else {
                val visibleItems = gridState.layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) 4
                else {
                    val maxCol = visibleItems.maxOfOrNull { it.column } ?: 0
                    maxCol + 1
                }
            }
        }
    }

    val focusGrid: () -> Unit = {
        coroutineScope.launch {
            val visibleIndices = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            val targetIndex = if (lastFocusedIndex in visibleIndices) {
                lastFocusedIndex
            } else if (visibleIndices.isNotEmpty()) {
                gridState.firstVisibleItemIndex
            } else {
                0
            }
            try {
                getFocusRequesterForIndex(targetIndex).requestFocusSafe()
            } catch (_: Exception) {
                try {
                    getFocusRequesterForIndex(0).requestFocusSafe()
                } catch (_: Exception) {}
            }
        }
    }

    // По умолчанию на телевизоре курсор должен стоять на строке поиска
    LaunchedEffect(Unit) {
        try {
            searchBarFocusRequester.requestFocusSafe()
        } catch (_: Exception) {}
    }

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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactHeight = maxHeight < 500.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (isCompactHeight) 6.dp else 10.dp, start = 16.dp, end = 16.dp)
        ) {
            var searchInput by remember { mutableStateOf(viewModel.searchQuery) }

            LaunchedEffect(viewModel.searchQuery) {
                if (searchInput != viewModel.searchQuery) {
                    searchInput = viewModel.searchQuery
                }
            }

            BackHandler(enabled = isTopScreen && searchInput.isNotEmpty()) {
                searchInput = ""
                viewModel.onSearchQueryChanged("")
            }

            val searchHistory by viewModel.searchHistory.collectAsState()

            // ---- 2. ВЫПАДАЮЩИЕ СПИСКИ И КОМПАКТНЫЙ ПОИСК ДЛЯ ТВ ----
            TvCatalogFiltersBar(
                currentType = currentType,
                currentSection = currentSection,
                currentGenre = currentGenre,
                genresList = genresList,
                searchQuery = searchInput,
                searchHistory = searchHistory,
                searchBarFocusRequester = searchBarFocusRequester,
                categoryFocusRequester = categoryDropdownFocusRequester,
                sectionFocusRequester = sectionDropdownFocusRequester,
                genreFocusRequester = genreDropdownFocusRequester,
                onFocusGrid = focusGrid,
                sidebarFocusRequester = sidebarFocusRequester,
                onSearchQueryChanged = {
                    searchInput = it
                    viewModel.onSearchQueryChanged(it)
                },
                onSearchCommit = {
                    viewModel.commitSearchQuery(searchInput)
                },
                onTypeSelected = { type ->
                    searchInput = ""
                    viewModel.loadCatalog(type = type, genre = "", forceRefresh = true)
                },
                onSectionSelected = { section ->
                    searchInput = ""
                    viewModel.loadCatalog(section = section, forceRefresh = true)
                },
                onGenreSelected = { genreSlug ->
                    searchInput = ""
                    viewModel.loadCatalog(genre = genreSlug, forceRefresh = true)
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Засчитываем запрос в историю поиска при начале скролла результатов на ТВ
            LaunchedEffect(gridState.isScrollInProgress) {
                if (gridState.isScrollInProgress && searchInput.isNotBlank()) {
                    viewModel.commitSearchQuery(searchInput)
                }
            }

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
                    val tvGridCols = parsedCardGrid?.columns
                    LazyVerticalGrid(
                        columns = if (tvGridCols != null) GridCells.Fixed(tvGridCols) else GridCells.Adaptive(minSize = 150.dp),
                        state = gridState,
                        contentPadding = PaddingValues(bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(if ((tvGridCols ?: 0) >= 7) 8.dp else 14.dp),
                        verticalArrangement = Arrangement.spacedBy(if ((tvGridCols ?: 0) >= 7) 10.dp else 16.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("tv_catalog_grid")
                    ) {
                        itemsIndexed(
                            items = catalogState.items,
                            key = { _, item -> item.id }
                        ) { index, item ->
                            val navigateToItem: (Int) -> Unit = { targetIndex ->
                                val total = catalogState.items.size
                                if (total > 0) {
                                    val clampedIndex = targetIndex.coerceIn(0, total - 1)
                                    coroutineScope.launch {
                                        try {
                                            val isVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == clampedIndex }
                                            if (!isVisible) {
                                                gridState.scrollToItem(clampedIndex)
                                            }
                                        } catch (_: Exception) {}
                                        getFocusRequesterForIndex(clampedIndex).requestFocusSafe()
                                    }
                                }
                            }

                            TvMovieCard(
                                item = item,
                                index = index,
                                totalItems = catalogState.items.size,
                                columnCount = columnCount,
                                onClick = {
                                    viewModel.commitSearchQuery(searchInput)
                                    onNavigateToDetail(item)
                                },
                                onFocused = {
                                    lastFocusedIndex = index
                                    if (searchInput.isNotBlank()) {
                                        viewModel.commitSearchQuery(searchInput)
                                    }
                                },
                                focusRequester = getFocusRequesterForIndex(index),
                                onNavigateIndex = navigateToItem,
                                onUp = { categoryDropdownFocusRequester.requestFocusSafe() },
                                onLeft = { sidebarFocusRequester.requestFocusSafe() }
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
}

/**
 * Динамический Hero баннер для ТВ:
 * Отображает постер, название, рейтинг и краткую информацию о фильме, на котором находится фокус пульта.
 */
@Composable
private fun TvHeroPreview(
    item: RezkaItem?,
    isCompact: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isCompact) 115.dp else 160.dp)
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
                        .padding(
                            start = if (isCompact) 14.dp else 20.dp,
                            top = if (isCompact) 10.dp else 16.dp,
                            bottom = if (isCompact) 10.dp else 16.dp,
                            end = 12.dp
                        ),
                    verticalArrangement = Arrangement.Center
                ) {
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
                                    fontSize = if (isCompact) 10.sp else 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = item.subtitle,
                            color = CinemaTextGray,
                            fontSize = if (isCompact) 11.sp else 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(if (isCompact) 3.dp else 6.dp))

                    Text(
                        text = item.title,
                        color = CinemaTextWhite,
                        fontSize = if (isCompact) 16.sp else 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(if (isCompact) 2.dp else 4.dp))
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
 * Панель фильтров каталога для ТВ:
 * 3 выпадающих списка (Категория, Раздел, Жанр) + компактное окно поиска.
 */
@Composable
private fun TvCatalogFiltersBar(
    currentType: RezkaType,
    currentSection: SectionType,
    currentGenre: String,
    genresList: List<GenreItem>,
    searchQuery: String,
    searchHistory: List<String> = emptyList(),
    onSearchQueryChanged: (String) -> Unit,
    onSearchCommit: (() -> Unit)? = null,
    onTypeSelected: (RezkaType) -> Unit,
    onSectionSelected: (SectionType) -> Unit,
    onGenreSelected: (String) -> Unit,
    searchBarFocusRequester: FocusRequester,
    categoryFocusRequester: FocusRequester,
    sectionFocusRequester: FocusRequester,
    genreFocusRequester: FocusRequester,
    onFocusGrid: () -> Unit,
    sidebarFocusRequester: FocusRequester
) {
    var isSearchEditing by remember { mutableStateOf(false) }
    val firstHistoryFocusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Поиск (сверху) - оптимизированная и аккуратная строка поиска для ТВ
        TvCompactSearchBar(
            query = searchQuery,
            onQueryChanged = onSearchQueryChanged,
            searchBarFocusRequester = searchBarFocusRequester,
            onLeft = { sidebarFocusRequester.requestFocusSafe() },
            onDown = {
                if (isSearchEditing && searchHistory.isNotEmpty()) {
                    firstHistoryFocusRequester.requestFocusSafe()
                } else {
                    categoryFocusRequester.requestFocusSafe()
                }
            },
            onSearchCommit = onSearchCommit,
            onEditingChange = { editing -> isSearchEditing = editing },
            modifier = Modifier.fillMaxWidth()
        )

        // Подсказки недавних запросов из истории поиска для ТВ (отображаются ТОЛЬКО по клику в поисковую строку)
        if (isSearchEditing && searchHistory.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = CinemaPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "История:",
                    color = CinemaTextGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                searchHistory.take(5).forEachIndexed { index, histItem ->
                    Surface(
                        color = CinemaDark,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, CinemaBorder),
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstHistoryFocusRequester) else Modifier)
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                                    categoryFocusRequester.requestFocusSafe()
                                    true
                                } else false
                            }
                            .tvFocusableItem(
                                onClick = {
                                    onSearchQueryChanged(histItem)
                                    onSearchCommit?.invoke()
                                    isSearchEditing = false
                                },
                                scaleFactor = 1.05f,
                                shape = RoundedCornerShape(6.dp)
                            )
                    ) {
                        Text(
                            text = histItem,
                            color = CinemaTextWhite,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // 2. Выпадающие списки (снизу) - Категория, Раздел, Жанр
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dropdown 1: Категория
            val categories = remember {
                listOf(
                    RezkaType.MOVIE to "Фильмы",
                    RezkaType.SERIES to "Сериалы",
                    RezkaType.ANIME to "Аниме",
                    RezkaType.CARTOON to "Мультики"
                )
            }
            val currentCategoryPair = categories.find { it.first == currentType } ?: categories[0]

            TvRezkaDropdown(
                label = "Категория",
                options = categories,
                selectedOption = currentCategoryPair,
                onOptionSelected = { pair -> onTypeSelected(pair.first) },
                getLabel = { it.second },
                modifier = Modifier.weight(1f),
                focusRequester = categoryFocusRequester,
                onUp = { searchBarFocusRequester.requestFocusSafe() },
                onLeft = { sidebarFocusRequester.requestFocusSafe() },
                onRight = { sectionFocusRequester.requestFocusSafe() },
                onDown = onFocusGrid
            )

            // Dropdown 2: Раздел
            val sections = remember {
                listOf(
                    SectionType.LATEST,
                    SectionType.POPULAR,
                    SectionType.WATCHING,
                    SectionType.AWAITING
                )
            }

            TvRezkaDropdown(
                label = "Раздел",
                options = sections,
                selectedOption = currentSection,
                onOptionSelected = onSectionSelected,
                getLabel = { it.getDisplayName() },
                modifier = Modifier.weight(1f),
                focusRequester = sectionFocusRequester,
                onUp = { searchBarFocusRequester.requestFocusSafe() },
                onLeft = { categoryFocusRequester.requestFocusSafe() },
                onRight = { genreFocusRequester.requestFocusSafe() },
                onDown = onFocusGrid
            )

            // Dropdown 3: Жанр
            val currentGenreItem = genresList.find { it.slug == currentGenre }
                ?: genresList.firstOrNull()
                ?: GenreItem("Все жанры", "")

            TvRezkaDropdown(
                label = "Жанр",
                options = genresList,
                selectedOption = currentGenreItem,
                onOptionSelected = { genreItem -> onGenreSelected(genreItem.slug) },
                getLabel = { it.name },
                modifier = Modifier.weight(1.1f),
                focusRequester = genreFocusRequester,
                onUp = { searchBarFocusRequester.requestFocusSafe() },
                onLeft = { sectionFocusRequester.requestFocusSafe() },
                onDown = onFocusGrid
            )
        }
    }
}

/**
 * Выпадающий список (Dropdown) для ТВ-интерфейса.
 * Поддерживает D-Pad навигацию, подсветку курсора пульта и выбор вариантов в меню.
 */
@Composable
fun <T> TvRezkaDropdown(
    label: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    getLabel: (T) -> String,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    lazyListState: androidx.compose.foundation.lazy.LazyListState? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val triggerRequester = focusRequester ?: remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(expanded) {
        if (!expanded) {
            triggerRequester.requestFocusSafe()
        }
    }

    Box(modifier = modifier) {
        Surface(
            color = CinemaDark,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                1.dp,
                if (expanded) CinemaPrimary else Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .then(surfaceModifier)
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                if (onUp != null) { onUp(); true } else false
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (onDown != null) { onDown(); true } else false
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (onLeft != null) { onLeft(); true } else false
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (onRight != null) { onRight(); true } else false
                            }
                            else -> false
                        }
                    } else false
                }
                .tvFocusableItem(
                    onClick = { expanded = !expanded },
                    scaleFactor = 1.04f,
                    shape = RoundedCornerShape(10.dp),
                    focusRequester = triggerRequester,
                    lazyListState = lazyListState
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
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "$label:",
                        color = CinemaTextGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    val selTrans = selectedOption as? Translator
                    if (selTrans != null && selTrans.flagUrl.isNotEmpty()) {
                        AsyncImage(
                            model = selTrans.flagUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(12.dp)
                                .widthIn(max = 20.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                    Text(
                        text = getLabel(selectedOption),
                        color = CinemaTextWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (selTrans != null && selTrans.isPremium && selTrans.premiumUrl.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        AsyncImage(
                            model = selTrans.premiumUrl,
                            contentDescription = "Премиум",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(12.dp)
                                .widthIn(max = 20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = CinemaPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                coroutineScope.launch {
                    triggerRequester.requestFocusSafe()
                }
            },
            properties = PopupProperties(focusable = true),
            modifier = Modifier
                .background(CinemaDark)
                .border(1.dp, CinemaPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .widthIn(min = 180.dp, max = 280.dp)
                .heightIn(max = 340.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selectedOption
                val optTrans = option as? Translator
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (optTrans != null && optTrans.flagUrl.isNotEmpty()) {
                                AsyncImage(
                                    model = optTrans.flagUrl,
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
                                text = getLabel(option),
                                color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (optTrans != null && optTrans.isPremium && optTrans.premiumUrl.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                AsyncImage(
                                    model = optTrans.premiumUrl,
                                    contentDescription = "Премиум",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .height(12.dp)
                                        .widthIn(max = 20.dp)
                                )
                            }
                        }
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = CinemaPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        expanded = false
                        onOptionSelected(option)
                        coroutineScope.launch {
                            triggerRequester.requestFocusSafe()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusableItem(
                            onClick = {
                                expanded = false
                                onOptionSelected(option)
                                coroutineScope.launch {
                                    triggerRequester.requestFocusSafe()
                                }
                            },
                            scaleFactor = 1.02f,
                            shape = RoundedCornerShape(6.dp)
                        ),
                    colors = MenuDefaults.itemColors(
                        textColor = CinemaTextWhite
                    )
                )
            }
        }
    }
}

/**
 * Компактная поисковая строка для ТВ-интерфейса.
 * Не занимает лишнего места по высоте и ширине, адаптирована под ТВ-пульт.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvCompactSearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    searchBarFocusRequester: FocusRequester? = null,
    onLeft: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSearchCommit: (() -> Unit)? = null,
    onEditingChange: ((Boolean) -> Unit)? = null
) {
    var isEditing by remember { mutableStateOf(false) }
    var hasBeenFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // При изменении состояния редактирования убираем или показываем историю поиска
    LaunchedEffect(isEditing) {
        onEditingChange?.invoke(isEditing)
        if (!isEditing) {
            hasBeenFocused = false
            searchBarFocusRequester?.requestFocusSafe()
        }
    }

    // При нажатии кнопки Назад пульта выходим из режима поиска и скрываем историю
    BackHandler(enabled = isEditing) {
        isEditing = false
        onEditingChange?.invoke(false)
        keyboardController?.hide()
    }

    Surface(
        color = CinemaDark,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (query.isNotEmpty() || isEditing) CinemaPrimary else Color.White.copy(alpha = 0.15f)
        ),
        modifier = modifier
            .height(42.dp)
            .focusProperties {
                // Запрещаем переход фокуса ВВЕРХ с поисковой строки в боковое меню.
                up = FocusRequester.Cancel
            }
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!isEditing && onLeft != null) {
                                onLeft()
                                true
                            } else false
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (onDown != null) {
                                onDown()
                                true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
            .then(
                if (!isEditing) {
                    Modifier.tvFocusableItem(
                        onClick = {
                            isEditing = true
                            hasBeenFocused = false
                            onEditingChange?.invoke(true)
                        },
                        scaleFactor = 1.02f,
                        focusedBorderWidth = 2.dp,
                        shape = RoundedCornerShape(10.dp),
                        focusRequester = searchBarFocusRequester
                    )
                } else Modifier
            )
            .testTag("tv_search_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Поиск",
                tint = if (isEditing || query.isNotEmpty()) CinemaPrimary else CinemaTextGray,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (!isEditing) {
                    // В обычном режиме навигации с пульта отображается только текст превью.
                    // Клавиатура НЕ выскакивает при простом перемещении курсора D-Pad!
                    Text(
                        text = if (query.isNotEmpty()) query else "Поиск фильмов, сериалов, аниме...",
                        color = if (query.isNotEmpty()) CinemaTextWhite else CinemaMuted,
                        fontSize = 13.sp,
                        fontWeight = if (query.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // Режим редактирования: активируется ТОЛЬКО по явному нажатию кнопки ОК на пульте
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocusSafe()
                        keyboardController?.show()
                    }

                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChanged,
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = CinemaTextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(CinemaPrimary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                onSearchCommit?.invoke()
                                isEditing = false
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown &&
                                    (keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                     keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
                                     keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER)
                                ) {
                                    onSearchCommit?.invoke()
                                    isEditing = false
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    true
                                } else false
                            }
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    hasBeenFocused = true
                                } else if (hasBeenFocused && isEditing) {
                                    isEditing = false
                                    keyboardController?.hide()
                                }
                            },
                        decorationBox = { innerTextField ->
                            if (query.isEmpty()) {
                                Text(
                                    text = "Введите название...",
                                    color = CinemaMuted,
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            if (query.isNotEmpty()) {
                IconButton(
                    onClick = {
                        onQueryChanged("")
                        isEditing = false
                        keyboardController?.hide()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Очистить",
                        tint = CinemaTextGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
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
    index: Int,
    totalItems: Int,
    columnCount: Int,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onNavigateIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null
) {
    val safeCols = if (columnCount > 0) columnCount else 4
    val isFirstColumn = index % safeCols == 0

    val isDense = columnCount >= 6
    val isUltraDense = columnCount >= 8

    Card(
        modifier = modifier
            .fillMaxWidth()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            val targetIndex = index + safeCols
                            if (targetIndex < totalItems) {
                                onNavigateIndex(targetIndex)
                                true
                            } else if (index < totalItems - 1) {
                                onNavigateIndex(totalItems - 1)
                                true
                            } else true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            if (index >= safeCols) {
                                onNavigateIndex(index - safeCols)
                                true
                            } else if (onUp != null) {
                                onUp()
                                true
                            } else false
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (isFirstColumn) {
                                if (onLeft != null) {
                                    onLeft()
                                    true
                                } else false
                            } else {
                                onNavigateIndex(index - 1)
                                true
                            }
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if ((index + 1) % safeCols == 0 || index == totalItems - 1) {
                                true
                            } else {
                                onNavigateIndex(index + 1)
                                true
                            }
                        }
                        else -> false
                    }
                } else false
            }
            .tvFocusableItem(
                onClick = onClick,
                onFocused = onFocused,
                scaleFactor = if (isDense) 1.05f else 1.08f,
                focusedBorderWidth = if (isDense) 2.dp else 3.dp,
                shape = RoundedCornerShape(if (isDense) 8.dp else 12.dp),
                focusRequester = focusRequester
            )
            .testTag("tv_movie_card_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = CinemaDark),
        shape = RoundedCornerShape(if (isDense) 8.dp else 12.dp)
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
                            .padding(if (isDense) 4.dp else 6.dp)
                            .background(CinemaPrimary, RoundedCornerShape(if (isDense) 4.dp else 6.dp))
                            .padding(horizontal = if (isDense) 4.dp else 6.dp, vertical = if (isDense) 2.dp else 3.dp)
                    ) {
                        Text(
                            text = item.rating,
                            color = Color.White,
                            fontSize = if (isDense) 8.sp else 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (isDense) 5.dp else 8.dp)
            ) {
                Text(
                    text = item.title,
                    color = CinemaTextWhite,
                    fontSize = if (isUltraDense) 9.sp else if (isDense) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    color = CinemaTextGray,
                    fontSize = if (isUltraDense) 7.sp else if (isDense) 8.sp else 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
