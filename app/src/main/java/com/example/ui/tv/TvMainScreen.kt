package com.example.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import com.example.ui.haptics.bounceOverscroll
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.ui.util.AdaptivePosterBadge
import com.example.ui.util.PosterBadgeEngine
import com.example.ui.util.SeriesBadgeMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.example.ui.util.rememberSavedLazyGridState
import com.example.ui.util.VoiceSearchButton
import com.example.ui.util.rememberVoiceSearchLauncher
import com.example.data.*
import com.example.ui.CatalogState
import com.example.ui.RezkaViewModel
import com.example.ui.screens.CollectionCard
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.components.AuthDialog
import com.example.ui.components.TvCountryDropdown
import com.example.ui.components.UserAvatar
import com.example.ui.effects.subtleCardBounce
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
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvMainScreen(
    viewModel: RezkaViewModel,
    onNavigateToDetail: (RezkaItem) -> Unit,
    onNavigateToThematic: (String, String) -> Unit = { _, _ -> },
    isTopScreen: Boolean = true,
    modifier: Modifier = Modifier
) {
    var selectedDestination by remember { mutableStateOf(TvNavDestination.CATALOG) }
    var isSidebarFocused by remember { mutableStateOf(false) }

    LaunchedEffect(selectedDestination, isTopScreen) {
        if (selectedDestination == TvNavDestination.HISTORY && isTopScreen) {
            viewModel.checkHistorySeriesUpdates(force = true)
        }
    }

    val isOnline by viewModel.isOnline.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentUserAvatar by viewModel.currentUserAvatar.collectAsState()
    var showAuthDialog by remember { mutableStateOf(false) }
    val accountButtonFocusRequester = remember { FocusRequester() }

    if (showAuthDialog) {
        AuthDialog(
            viewModel = viewModel,
            isTvMode = true,
            onDismiss = {
                showAuthDialog = false
                accountButtonFocusRequester.requestFocusSafe()
            }
        )
    }

    val catalogState by viewModel.catalogState.collectAsState()
    val collectionsState by viewModel.collectionsState.collectAsState()
    val currentType by viewModel.currentType.collectAsState()
    val currentSection by viewModel.currentSection.collectAsState()
    val currentGenre by viewModel.currentGenre.collectAsState()
    val genresList by viewModel.genresList.collectAsState()
    val currentYear by viewModel.currentYear.collectAsState()
    val yearsList by viewModel.yearsList.collectAsState()
    val currentCountry by viewModel.currentCountry.collectAsState()
    val countriesList by viewModel.countriesList.collectAsState()
    val loadingMovieId by viewModel.loadingMovieId.collectAsState()
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
        val sidebarFocusRequesters = remember {
            TvNavDestination.values().associateWith { FocusRequester() }
        }
        val rightContentFocusRequester = remember { FocusRequester() }
        val favoritesFirstItemRequester = remember { FocusRequester() }
        val historyFirstItemRequester = remember { FocusRequester() }
        val settingsFirstCategoryRequester = remember { FocusRequester() }
        var catalogFocusRestorer by remember { mutableStateOf<(() -> Unit)?>(null) }

        val navigateToCurrentMainContent: () -> Unit = {
            when (selectedDestination) {
                TvNavDestination.CATALOG -> {
                    if (catalogFocusRestorer != null) {
                        catalogFocusRestorer?.invoke()
                    } else {
                        rightContentFocusRequester.requestFocusSafe()
                    }
                }
                TvNavDestination.FAVORITES -> favoritesFirstItemRequester.requestFocusSafe()
                TvNavDestination.HISTORY -> historyFirstItemRequester.requestFocusSafe()
                TvNavDestination.SETTINGS -> settingsFirstCategoryRequester.requestFocusSafe()
            }
        }

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
                    focusRequester = accountButtonFocusRequester,
                    onRight = navigateToCurrentMainContent,
                    onClick = { showAuthDialog = true }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Navigation Destinations
                TvNavDestination.values().forEach { dest ->
                    val isSelected = selectedDestination == dest
                    val title = if (dest == TvNavDestination.CATALOG && !isOnline) "Оффлайн" else dest.title
                    val icon = if (dest == TvNavDestination.CATALOG && !isOnline) Icons.Default.CloudOff else dest.icon
                    TvSidebarButton(
                        destination = dest,
                        isSelected = isSelected,
                        isExpanded = isSidebarFocused,
                        overrideTitle = title,
                        overrideIcon = icon,
                        focusRequester = sidebarFocusRequesters[dest],
                        onRight = navigateToCurrentMainContent,
                        onClick = { selectedDestination = dest }
                    )
                }
            }

            val updateState by UpdateManager.updateState.collectAsState()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            if (updateState !is UpdateState.Idle) {
                TvUpdateSidebarItem(
                    updateState = updateState,
                    isExpanded = isSidebarFocused,
                    onRight = navigateToCurrentMainContent,
                    onClick = {
                        scope.launch {
                            when (val state = updateState) {
                                is UpdateState.UpdateAvailable -> {
                                    UpdateManager.startDownload(context, state.downloadUrl)
                                }
                                is UpdateState.ReadyToInstall -> {
                                    UpdateManager.installApk(context, state.apkFile)
                                }
                                is UpdateState.Error -> {
                                    UpdateManager.dismissUpdate()
                                }
                                else -> {}
                            }
                        }
                    }
                )
            }
        }

        // ---- 2. MAIN CONTENT AREA ----
        // Полная изоляция курсора: курсор действует внутри основного окна,
        // а на боковую панель переходит ИСКЛЮЧИТЕЛЬНО по нажатию влево!
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusProperties {
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                    left = FocusRequester.Cancel
                }
        ) {
            when (selectedDestination) {
                TvNavDestination.CATALOG -> {
                    TvCatalogContent(
                        viewModel = viewModel,
                        catalogState = catalogState,
                        collectionsState = collectionsState,
                        currentType = currentType,
                        currentSection = currentSection,
                        currentGenre = currentGenre,
                        genresList = genresList,
                        currentYear = currentYear,
                        yearsList = yearsList,
                        currentCountry = currentCountry,
                        countriesList = countriesList,
                        isLoadingMore = isLoadingMore,
                        isEndReached = isEndReached,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToThematic = onNavigateToThematic,
                        sidebarFocusRequester = sidebarFocusRequesters[TvNavDestination.CATALOG] ?: remember { FocusRequester() },
                        entryFocusRequester = rightContentFocusRequester,
                        isTopScreen = isTopScreen,
                        onRegisterFocusRestorer = { restorer -> catalogFocusRestorer = restorer }
                    )
                }
                TvNavDestination.FAVORITES -> {
                    FavoritesScreen(
                        viewModel = viewModel,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateLeftToSidebar = { sidebarFocusRequesters[TvNavDestination.FAVORITES]?.requestFocusSafe() },
                        firstItemFocusRequester = favoritesFirstItemRequester
                    )
                }
                TvNavDestination.HISTORY -> {
                    HistoryScreen(
                        viewModel = viewModel,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateLeftToSidebar = { sidebarFocusRequesters[TvNavDestination.HISTORY]?.requestFocusSafe() },
                        firstItemFocusRequester = historyFirstItemRequester
                    )
                }
                TvNavDestination.SETTINGS -> {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = null,
                        onNavigateLeftToSidebar = { sidebarFocusRequesters[TvNavDestination.SETTINGS]?.requestFocusSafe() },
                        firstCategoryFocusRequester = settingsFirstCategoryRequester
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
    overrideTitle: String? = null,
    overrideIcon: ImageVector? = null,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onRight: (() -> Unit)? = null
) {
    val bgColor = if (isSelected) CinemaPrimary.copy(alpha = 0.2f) else Color.Transparent
    val contentColor = if (isSelected) CinemaPrimary else CinemaTextWhite
    val displayTitle = overrideTitle ?: destination.title
    val displayIcon = overrideIcon ?: destination.icon

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(bgColor, RoundedCornerShape(10.dp))
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
                scaleFactor = 1.0f,
                focusedBorderWidth = 2.dp,
                shape = RoundedCornerShape(10.dp),
                focusRequester = focusRequester
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = displayIcon,
            contentDescription = displayTitle,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        if (isExpanded) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = displayTitle,
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
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onRight: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(
                if (isLoggedIn) CinemaCard.copy(alpha = 0.5f) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
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
                scaleFactor = 1.0f,
                focusedBorderWidth = 2.dp,
                shape = RoundedCornerShape(10.dp),
                focusRequester = focusRequester
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
    collectionsState: CollectionsState,
    currentType: RezkaType,
    currentSection: SectionType,
    currentGenre: String,
    genresList: List<GenreItem>,
    currentYear: String,
    yearsList: List<YearItem>,
    currentCountry: String,
    countriesList: List<CountryItem>,
    isLoadingMore: Boolean,
    isEndReached: Boolean,
    onNavigateToDetail: (RezkaItem) -> Unit,
    onNavigateToThematic: (String, String) -> Unit,
    sidebarFocusRequester: FocusRequester,
    entryFocusRequester: FocusRequester,
    isTopScreen: Boolean = true,
    onRegisterFocusRestorer: (((() -> Unit)) -> Unit)? = null
) {
    val gridState = rememberSavedLazyGridState("catalog", viewModel)
    var lastFocusedIndex by rememberSaveable { mutableStateOf(viewModel.getTvCatalogFocusedIndex() ?: 0) }
    var isFiltersAreaFocused by remember { mutableStateOf(false) }

    val currentCatalogKey = remember(currentType, currentSection, currentGenre, currentYear, currentCountry, viewModel.searchQuery) {
        "${currentType.name}_${currentSection.name}_${currentGenre}_${currentYear}_${currentCountry}_${viewModel.searchQuery.trim()}"
    }
    var lastRenderedCatalogKey by rememberSaveable { mutableStateOf(currentCatalogKey) }

    val searchBarFocusRequester = remember { FocusRequester() }
    val categoryDropdownFocusRequester = remember { FocusRequester() }
    val sectionDropdownFocusRequester = remember { FocusRequester() }
    val genreDropdownFocusRequester = remember { FocusRequester() }
    val yearDropdownFocusRequester = remember { FocusRequester() }
    val countryDropdownFocusRequester = remember { FocusRequester() }

    val coroutineScope = rememberCoroutineScope()
    val loadingMovieId by viewModel.loadingMovieId.collectAsState()
    val cardGridMode by viewModel.cardGridMode.collectAsState()
    val seriesBadgeMode by viewModel.seriesBadgeMode.collectAsState()
    val parsedCardGrid = remember(cardGridMode) { RezkaService.parseCardGrid(cardGridMode) }
    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun getFocusRequesterForIndex(index: Int): FocusRequester {
        return if (index == 0) entryFocusRequester else itemFocusRequesters.getOrPut(index) { FocusRequester() }
    }

    val restoreCatalogFocus: () -> Unit = {
        coroutineScope.launch {
            if (isFiltersAreaFocused) {
                categoryDropdownFocusRequester.requestFocusSafe()
            } else {
                val visibleIndices = gridState.layoutInfo.visibleItemsInfo.map { it.index }
                val targetIndex = when {
                    lastFocusedIndex in visibleIndices -> lastFocusedIndex
                    visibleIndices.isNotEmpty() -> visibleIndices.first()
                    else -> 0
                }
                try {
                    getFocusRequesterForIndex(targetIndex).requestFocusSafe()
                } catch (_: Exception) {
                    try {
                        entryFocusRequester.requestFocusSafe()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    LaunchedEffect(restoreCatalogFocus) {
        onRegisterFocusRestorer?.invoke(restoreCatalogFocus)
    }

    LaunchedEffect(currentCatalogKey) {
        if (currentCatalogKey != lastRenderedCatalogKey) {
            lastRenderedCatalogKey = currentCatalogKey
            gridState.scrollToItem(0, 0)
            lastFocusedIndex = 0
            viewModel.clearTvCatalogFocusedItem()
            viewModel.resetScrollPosition("catalog")
            coroutineScope.launch {
                for (attempt in 0..14) {
                    kotlinx.coroutines.delay(if (attempt == 0) 30L else 50L)
                    try {
                        getFocusRequesterForIndex(0).requestFocus()
                        break
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.catalogScrollResetEvent.collect {
            gridState.scrollToItem(0, 0)
            lastFocusedIndex = 0
            viewModel.clearTvCatalogFocusedItem()
            try {
                getFocusRequesterForIndex(0).requestFocusSafe()
            } catch (_: Exception) {}
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

    // Фильтрация элементов по стране
    val displayedItems = remember(catalogState, currentCountry) {
        if (catalogState is CatalogState.Success) {
            if (currentCountry.isEmpty()) {
                catalogState.items
            } else {
                catalogState.items.filter { it.matchesCountry(currentCountry) }
            }
        } else emptyList()
    }

    LaunchedEffect(displayedItems.size, currentCountry, isEndReached, isLoadingMore) {
        if (currentCountry.isNotEmpty() && displayedItems.size < 20 && !isEndReached && !isLoadingMore && viewModel.searchQuery.isEmpty()) {
            viewModel.loadNextPage()
        }
    }

    var hasRestoredFocus by remember { mutableStateOf(false) }

    LaunchedEffect(catalogState, collectionsState, isTopScreen) {
        if (!isTopScreen) {
            hasRestoredFocus = false
            return@LaunchedEffect
        }
        if (hasRestoredFocus) return@LaunchedEffect
        if (currentType != RezkaType.COLLECTIONS && catalogState is CatalogState.Success) {
            val items = displayedItems
            if (items.isNotEmpty()) {
                val savedItemId = viewModel.getTvCatalogFocusedItemId()
                val savedIndex = viewModel.getTvCatalogFocusedIndex()

                val targetIndex = when {
                    savedItemId != null -> {
                        val idx = items.indexOfFirst { it.id == savedItemId }
                        if (idx >= 0) idx else (savedIndex?.coerceIn(0, items.lastIndex) ?: 0)
                    }
                    savedIndex != null -> savedIndex.coerceIn(0, items.lastIndex)
                    else -> 0
                }

                hasRestoredFocus = true
                lastFocusedIndex = targetIndex
                try {
                    gridState.scrollToItem(targetIndex)
                } catch (_: Throwable) {}

                var focused = false
                for (attempt in 0..14) {
                    kotlinx.coroutines.delay(if (attempt == 0) 40L else 50L)
                    try {
                        val requester = getFocusRequesterForIndex(targetIndex)
                        requester.requestFocus()
                        focused = true
                        break
                    } catch (_: Throwable) {}
                }
                if (!focused) {
                    try {
                        getFocusRequesterForIndex(0).requestFocusSafe()
                    } catch (_: Exception) {}
                }
                return@LaunchedEffect
            }
        } else if (currentType == RezkaType.COLLECTIONS && collectionsState is CollectionsState.Success) {
            val items = collectionsState.items
            if (items.isNotEmpty()) {
                hasRestoredFocus = true
                try {
                    gridState.scrollToItem(0)
                    getFocusRequesterForIndex(0).requestFocusSafe()
                } catch (_: Exception) {}
                return@LaunchedEffect
            }
        }

        if (!hasRestoredFocus) {
            hasRestoredFocus = true
            try {
                getFocusRequesterForIndex(0).requestFocusSafe()
            } catch (_: Exception) {}
        }
    }

    // Пагинация для ТВ-сетки без ошибочного canScrollForward и с поддержкой фильтра по странам
    val shouldLoadMore by remember(displayedItems.size, currentCountry) {
        derivedStateOf {
            val totalItems = displayedItems.size
            if (totalItems == 0) return@derivedStateOf false
            val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val threshold = if (currentCountry.isNotEmpty()) 10 else 6
            lastVisibleIndex >= totalItems - threshold
        }
    }

    LaunchedEffect(shouldLoadMore, isLoadingMore, isEndReached) {
        if (shouldLoadMore && !isLoadingMore && !isEndReached && viewModel.searchQuery.isEmpty() && currentType != RezkaType.COLLECTIONS) {
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
            val searchCategoryFilter by viewModel.searchCategoryFilter.collectAsState()
            val searchSectionFilter by viewModel.searchSectionFilter.collectAsState()
            val searchGenreFilter by viewModel.searchGenreFilter.collectAsState()
            val searchYearFilter by viewModel.searchYearFilter.collectAsState()
            val searchCountryFilter by viewModel.searchCountryFilter.collectAsState()

            val isSearching = searchInput.isNotBlank()
            val activeSection = if (isSearching) searchSectionFilter else currentSection
            val activeGenre = if (isSearching) searchGenreFilter else currentGenre
            val activeYear = if (isSearching) searchYearFilter else currentYear
            val activeCountry = if (isSearching) searchCountryFilter else currentCountry

            // ---- 2. ВЫПАДАЮЩИЕ СПИСКИ И КОМПАКТНЫЙ ПОИСК ДЛЯ ТВ ----
            TvCatalogFiltersBar(
                currentType = currentType,
                searchCategoryFilter = searchCategoryFilter,
                currentSection = activeSection,
                currentGenre = activeGenre,
                genresList = genresList,
                currentYear = activeYear,
                yearsList = yearsList,
                currentCountry = activeCountry,
                countriesList = countriesList,
                searchQuery = searchInput,
                searchHistory = searchHistory,
                searchBarFocusRequester = searchBarFocusRequester,
                categoryFocusRequester = categoryDropdownFocusRequester,
                sectionFocusRequester = sectionDropdownFocusRequester,
                genreFocusRequester = genreDropdownFocusRequester,
                yearFocusRequester = yearDropdownFocusRequester,
                countryFocusRequester = countryDropdownFocusRequester,
                onFocusGrid = focusGrid,
                sidebarFocusRequester = sidebarFocusRequester,
                onSearchQueryChanged = {
                    searchInput = it
                    viewModel.onSearchQueryChanged(it)
                },
                onSearchCommit = {
                    viewModel.commitSearchQuery(searchInput)
                },
                onRemoveSearchQuery = { query ->
                    viewModel.removeSearchQueryFromHistory(query)
                },
                onClearSearchHistory = {
                    viewModel.clearSearchHistory()
                },
                onFiltersFocused = {
                    isFiltersAreaFocused = true
                },
                onTypeSelected = { type ->
                    viewModel.selectCategoryFilter(type)
                },
                onSectionSelected = { section ->
                    viewModel.selectSectionFilter(section)
                },
                onGenreSelected = { genreSlug ->
                    viewModel.selectGenreFilter(genreSlug)
                },
                onYearSelected = { yearItem ->
                    viewModel.selectYearFilter(yearItem.year)
                },
                onCountrySelected = { countryItem ->
                    viewModel.selectCountryFilter(countryItem.query)
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Засчитываем запрос в историю поиска при начале скролла результатов на ТВ
            LaunchedEffect(gridState.isScrollInProgress) {
                if (gridState.isScrollInProgress && searchInput.isNotBlank()) {
                    viewModel.commitSearchQuery(searchInput)
                }
            }

            // ---- 3. TV MOVIES / COLLECTIONS GRID ----
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (currentType == RezkaType.COLLECTIONS) {
                    when (val cState = collectionsState) {
                        is CollectionsState.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(48.dp))
                            }
                        }
                        is CollectionsState.Error -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(cState.message, color = CinemaTextWhite, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                        viewModel.loadCollections(forceRefresh = true)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary)
                                ) {
                                    Text("Повторить")
                                }
                            }
                        }
                        is CollectionsState.Success -> {
                            if (cState.items.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Подборок не найдено", color = CinemaTextGray, fontSize = 16.sp)
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    state = gridState,
                                    contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .bounceOverscroll(androidx.compose.foundation.gestures.Orientation.Vertical)
                                        .testTag("tv_collections_grid")
                                ) {
                                    itemsIndexed(
                                        items = cState.items,
                                        key = { _, item -> item.id }
                                    ) { index, collectionItem ->
                                        val navigateToItem: (Int) -> Unit = { targetIndex ->
                                            val total = cState.items.size
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

                                        CollectionCard(
                                            item = collectionItem,
                                            index = index,
                                            totalItems = cState.items.size,
                                            columnCount = 3,
                                            onNavigateIndex = navigateToItem,
                                            onUp = { categoryDropdownFocusRequester.requestFocusSafe() },
                                            onLeft = { sidebarFocusRequester.requestFocusSafe() },
                                            onClick = {
                                                viewModel.commitSearchQuery(searchInput)
                                                onNavigateToThematic(collectionItem.title, collectionItem.url)
                                            },
                                            focusRequester = getFocusRequesterForIndex(index)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
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
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                        viewModel.loadCatalog(forceRefresh = true)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary)
                                ) {
                                    Text("Повторить")
                                }
                            }
                        }
                        is CatalogState.Success -> {
                            val items = displayedItems

                            if (items.isEmpty()) {
                                if (isLoadingMore || (currentCountry.isNotEmpty() && !isEndReached)) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            CircularProgressIndicator(
                                                color = CinemaPrimary,
                                                modifier = Modifier.size(48.dp),
                                                strokeWidth = 3.5.dp
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = if (currentCountry.isNotEmpty()) "Поиск фильмов по выбранной стране..." else "Загрузка каталога...",
                                                color = CinemaTextWhite,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(32.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.SearchOff, contentDescription = null, tint = CinemaMuted, modifier = Modifier.size(64.dp))
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = if (currentCountry.isNotEmpty()) "Фильмы по данной стране не найдены" else "Ничего не найдено",
                                            color = CinemaTextGray,
                                            fontSize = 16.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                val resolvedGrid = remember(cardGridMode, maxWidth, maxHeight) {
                                    CardGridEngine.calculate(
                                        cardGridMode = cardGridMode,
                                        availableWidth = maxWidth,
                                        availableHeight = maxHeight,
                                        isLandscapeOrTv = true
                                    )
                                }

                                val tvGridCols = resolvedGrid.columns
                                val tvCardHeight = resolvedGrid.cardHeight

                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(tvGridCols),
                                    state = gridState,
                                    contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(resolvedGrid.horizontalSpacing),
                                    verticalArrangement = Arrangement.spacedBy(resolvedGrid.verticalSpacing),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .bounceOverscroll(androidx.compose.foundation.gestures.Orientation.Vertical)
                                        .testTag("tv_catalog_grid")
                                ) {
                                    itemsIndexed(
                                        items = items,
                                        key = { _, item -> item.id }
                                    ) { index, item ->
                                        val navigateToItem: (Int) -> Unit = { targetIndex ->
                                            val total = items.size
                                            if (total > 0) {
                                                val clampedIndex = targetIndex.coerceIn(0, total - 1)
                                                coroutineScope.launch {
                                                    try {
                                                        val isVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == clampedIndex }
                                                        if (!isVisible) {
                                                            gridState.scrollToItem(clampedIndex)
                                                        }
                                                        if (isLoadingMore && clampedIndex >= total - tvGridCols) {
                                                            try {
                                                                gridState.animateScrollToItem(items.size)
                                                            } catch (_: Exception) {}
                                                        }
                                                    } catch (_: Exception) {}
                                                    getFocusRequesterForIndex(clampedIndex).requestFocusSafe()
                                                }
                                            }
                                        }

                                        TvMovieCard(
                                            item = item,
                                            index = index,
                                            totalItems = items.size,
                                            columnCount = tvGridCols,
                                            cardHeight = tvCardHeight,
                                            cardWidth = resolvedGrid.estimatedCardWidth,
                                            seriesBadgeMode = seriesBadgeMode,
                                            isBouncing = item.id == loadingMovieId,
                                            onClick = {
                                                viewModel.commitSearchQuery(searchInput)
                                                viewModel.setTvCatalogFocusedItem(item.id, index)
                                                lastFocusedIndex = index
                                                onNavigateToDetail(item)
                                            },
                                            onFocused = {
                                                isFiltersAreaFocused = false
                                                lastFocusedIndex = index
                                                viewModel.setTvCatalogFocusedItem(item.id, index)
                                                if (searchInput.isNotBlank()) {
                                                    viewModel.commitSearchQuery(searchInput)
                                                }
                                            },
                                            focusRequester = getFocusRequesterForIndex(index),
                                            onNavigateIndex = navigateToItem,
                                            onUp = { categoryDropdownFocusRequester.requestFocusSafe() },
                                            onLeft = { sidebarFocusRequester.requestFocusSafe() },
                                            onDownAtEnd = {
                                                if (isLoadingMore) {
                                                    coroutineScope.launch {
                                                        try {
                                                            gridState.animateScrollToItem(items.size)
                                                        } catch (_: Exception) {}
                                                    }
                                                }
                                            }
                                        )
                                    }

                                    if (isLoadingMore) {
                                        item(span = { GridItemSpan(maxLineSpan) }) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 24.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    CircularProgressIndicator(
                                                        color = CinemaPrimary,
                                                        modifier = Modifier.size(32.dp),
                                                        strokeWidth = 3.dp
                                                    )
                                                    Text(
                                                        text = if (currentCountry.isNotEmpty()) "Поиск фильмов по стране..." else "Загрузка карточек...",
                                                        color = CinemaTextGray,
                                                        fontSize = 14.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Floating TV Loading Indicator
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isLoadingMore,
                                enter = fadeIn() + slideInVertically { it / 2 },
                                exit = fadeOut() + slideOutVertically { it / 2 },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(bottom = 24.dp, end = 24.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = CinemaDark.copy(alpha = 0.94f),
                                    border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f)),
                                    shadowElevation = 8.dp
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = CinemaPrimary,
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = if (currentCountry.isNotEmpty()) "Поиск фильмов..." else "Подгрузка карточек...",
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
                                    text = PosterBadgeEngine.getVariants(item.rating).clean.ifEmpty { item.rating },
                                    color = Color.White,
                                    fontSize = if (isCompact) 10.sp else 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
 * 5 выпадающих списков (Категория, Раздел, Жанр, Год, Страна) в одну полосу без заголовков + компактный поиск.
 */
@Composable
private fun TvCatalogFiltersBar(
    currentType: RezkaType,
    searchCategoryFilter: RezkaType? = null,
    currentSection: SectionType,
    currentGenre: String,
    genresList: List<GenreItem>,
    currentYear: String,
    yearsList: List<YearItem>,
    currentCountry: String,
    countriesList: List<CountryItem>,
    searchQuery: String,
    searchHistory: List<String> = emptyList(),
    onSearchQueryChanged: (String) -> Unit,
    onSearchCommit: (() -> Unit)? = null,
    onRemoveSearchQuery: ((String) -> Unit)? = null,
    onClearSearchHistory: (() -> Unit)? = null,
    onFiltersFocused: (() -> Unit)? = null,
    onTypeSelected: (RezkaType?) -> Unit,
    onSectionSelected: (SectionType) -> Unit,
    onGenreSelected: (String) -> Unit,
    onYearSelected: (YearItem) -> Unit,
    onCountrySelected: (CountryItem) -> Unit,
    searchBarFocusRequester: FocusRequester,
    categoryFocusRequester: FocusRequester,
    sectionFocusRequester: FocusRequester,
    genreFocusRequester: FocusRequester,
    yearFocusRequester: FocusRequester,
    countryFocusRequester: FocusRequester,
    onFocusGrid: () -> Unit,
    sidebarFocusRequester: FocusRequester
) {
    var isSearchInputFocused by remember { mutableStateOf(false) }
    var focusedHistoryIndex by remember { mutableStateOf<Int?>(null) }

    val recentQueries = remember(searchHistory) { searchHistory.take(5) }
    val hasSearchHistory = recentQueries.isNotEmpty()
    val historyFocusRequesters = remember(recentQueries) {
        recentQueries.map { FocusRequester() }
    }
    val deleteFocusRequesters = remember(recentQueries) {
        recentQueries.map { FocusRequester() }
    }
    val clearAllFocusRequester = remember { FocusRequester() }

    val navigateUpFromFilters: () -> Unit = {
        if (hasSearchHistory && historyFocusRequesters.isNotEmpty()) {
            val targetIdx = (focusedHistoryIndex ?: 0).coerceIn(0, historyFocusRequesters.lastIndex)
            historyFocusRequesters[targetIdx].requestFocusSafe()
        } else {
            searchBarFocusRequester.requestFocusSafe()
        }
    }

    val handleRemoveQuery: (Int, String) -> Unit = { index, query ->
        if (recentQueries.size <= 1) {
            searchBarFocusRequester.requestFocusSafe()
        } else if (index < recentQueries.lastIndex) {
            historyFocusRequesters.getOrNull(index + 1)?.requestFocusSafe()
        } else {
            historyFocusRequesters.getOrNull(index - 1)?.requestFocusSafe()
        }
        onRemoveSearchQuery?.invoke(query)
    }

    val handleClearAllHistory: () -> Unit = {
        searchBarFocusRequester.requestFocusSafe()
        onClearSearchHistory?.invoke()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val tvVoiceButtonFocusRequester = remember { FocusRequester() }
        val launchTvVoiceSearch = rememberVoiceSearchLauncher(
            prompt = "Назовите фильм или сериал"
        ) { spokenText ->
            onSearchQueryChanged(spokenText)
            onSearchCommit?.invoke()
        }

        // 1. Поиск (сверху) - строка поиска во всю ширину со встроенной кнопкой голосового ввода для ТВ
        TvCompactSearchBar(
            query = searchQuery,
            onQueryChanged = onSearchQueryChanged,
            searchBarFocusRequester = searchBarFocusRequester,
            voiceButtonFocusRequester = tvVoiceButtonFocusRequester,
            onVoiceSearchClick = launchTvVoiceSearch,
            onLeft = { sidebarFocusRequester.requestFocusSafe() },
            onDown = {
                if (hasSearchHistory && historyFocusRequesters.isNotEmpty()) {
                    val targetIdx = (focusedHistoryIndex ?: 0).coerceIn(0, historyFocusRequesters.lastIndex)
                    historyFocusRequesters[targetIdx].requestFocusSafe()
                } else {
                    categoryFocusRequester.requestFocusSafe()
                }
            },
            onSearchCommit = onSearchCommit,
            onFocusChanged = { focused ->
                isSearchInputFocused = focused
                if (focused) onFiltersFocused?.invoke()
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Подсказки недавних запросов из истории поиска для ТВ с возможностью удаления элементов и полной очистки
        if (hasSearchHistory) {
            val historyScrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(historyScrollState),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(end = 2.dp)
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
                }

                recentQueries.forEachIndexed { index, histItem ->
                    val histRequester = historyFocusRequesters.getOrNull(index) ?: remember { FocusRequester() }
                    val delRequester = deleteFocusRequesters.getOrNull(index) ?: remember { FocusRequester() }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Чип выбора поискового запроса
                        Surface(
                            color = CinemaDark,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, CinemaBorder),
                            modifier = Modifier
                                .focusRequester(histRequester)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                        when (keyEvent.nativeKeyEvent.keyCode) {
                                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                                searchBarFocusRequester.requestFocusSafe()
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                categoryFocusRequester.requestFocusSafe()
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                                if (index == 0) {
                                                    sidebarFocusRequester.requestFocusSafe()
                                                    true
                                                } else {
                                                    deleteFocusRequesters.getOrNull(index - 1)?.requestFocusSafe()
                                                    true
                                                }
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                delRequester.requestFocusSafe()
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_FORWARD_DEL,
                                            AndroidKeyEvent.KEYCODE_DEL -> {
                                                handleRemoveQuery(index, histItem)
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                .tvFocusableItem(
                                    onClick = {
                                        onSearchQueryChanged(histItem)
                                        onSearchCommit?.invoke()
                                    },
                                    onFocusChanged = { focused ->
                                        if (focused) {
                                            focusedHistoryIndex = index
                                            onFiltersFocused?.invoke()
                                        }
                                    },
                                    scaleFactor = 1.05f,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .testTag("tv_search_history_item_$index")
                        ) {
                            Text(
                                text = histItem,
                                color = CinemaTextWhite,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(max = 160.dp)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        // Кнопка удаления отдельного запроса (крестик)
                        Surface(
                            color = CinemaDark,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, CinemaBorder),
                            modifier = Modifier
                                .focusRequester(delRequester)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                        when (keyEvent.nativeKeyEvent.keyCode) {
                                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                                searchBarFocusRequester.requestFocusSafe()
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                categoryFocusRequester.requestFocusSafe()
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                                histRequester.requestFocusSafe()
                                                true
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                if (index < recentQueries.lastIndex) {
                                                    historyFocusRequesters.getOrNull(index + 1)?.requestFocusSafe()
                                                    true
                                                } else {
                                                    clearAllFocusRequester.requestFocusSafe()
                                                    true
                                                }
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                .tvFocusableItem(
                                    onClick = {
                                        handleRemoveQuery(index, histItem)
                                    },
                                    onFocusChanged = { focused ->
                                        if (focused) {
                                            focusedHistoryIndex = index
                                            onFiltersFocused?.invoke()
                                        }
                                    },
                                    scaleFactor = 1.05f,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .testTag("tv_search_history_delete_$index")
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Удалить запрос",
                                    tint = CinemaTextGray,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }

                // Кнопка очистки всей истории поиска
                Surface(
                    color = CinemaDark,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, CinemaBorder),
                    modifier = Modifier
                        .focusRequester(clearAllFocusRequester)
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                        searchBarFocusRequester.requestFocusSafe()
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                        categoryFocusRequester.requestFocusSafe()
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                        deleteFocusRequesters.lastOrNull()?.requestFocusSafe()
                                            ?: historyFocusRequesters.lastOrNull()?.requestFocusSafe()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                        .tvFocusableItem(
                            onClick = {
                                handleClearAllHistory()
                            },
                            onFocusChanged = { focused ->
                                if (focused) onFiltersFocused?.invoke()
                            },
                            scaleFactor = 1.05f,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .testTag("tv_clear_search_history_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Очистить всю историю",
                            tint = CinemaTextGray,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Очистить",
                            color = CinemaTextWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 2. Все 5 выпадающих списков в одну полосу без заголовков
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dropdown 1: Категория
            val isSearching = searchQuery.isNotBlank()
            val categories: List<Pair<RezkaType?, String>> = remember(isSearching) {
                if (isSearching) {
                    listOf(
                        null to "Все категории",
                        RezkaType.MOVIE to "Фильмы",
                        RezkaType.SERIES to "Сериалы",
                        RezkaType.ANIME to "Аниме",
                        RezkaType.CARTOON to "Мультики"
                    )
                } else {
                    listOf(
                        RezkaType.MOVIE to "Фильмы",
                        RezkaType.SERIES to "Сериалы",
                        RezkaType.ANIME to "Аниме",
                        RezkaType.CARTOON to "Мультики",
                        RezkaType.COLLECTIONS to "Подборки"
                    )
                }
            }
            val currentCategoryPair = if (isSearching) {
                categories.find { it.first == searchCategoryFilter } ?: categories[0]
            } else {
                categories.find { it.first == currentType } ?: categories[0]
            }

            TvRezkaDropdown(
                label = "",
                options = categories,
                selectedOption = currentCategoryPair,
                onOptionSelected = { pair -> onTypeSelected(pair.first) },
                getLabel = { it.second },
                modifier = Modifier.weight(1f),
                focusRequester = categoryFocusRequester,
                onUp = navigateUpFromFilters,
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
                label = "",
                options = sections,
                selectedOption = currentSection,
                onOptionSelected = onSectionSelected,
                getLabel = { it.getDisplayName() },
                modifier = Modifier.weight(1f),
                focusRequester = sectionFocusRequester,
                onUp = navigateUpFromFilters,
                onLeft = { categoryFocusRequester.requestFocusSafe() },
                onRight = { genreFocusRequester.requestFocusSafe() },
                onDown = onFocusGrid
            )

            // Dropdown 3: Жанр
            val currentGenreItem = genresList.find { it.slug == currentGenre }
                ?: genresList.firstOrNull()
                ?: GenreItem("Все жанры", "")

            TvRezkaDropdown(
                label = "",
                options = genresList,
                selectedOption = currentGenreItem,
                onOptionSelected = { genreItem -> onGenreSelected(genreItem.slug) },
                getLabel = { it.name },
                modifier = Modifier.weight(1f),
                focusRequester = genreFocusRequester,
                onUp = navigateUpFromFilters,
                onLeft = { sectionFocusRequester.requestFocusSafe() },
                onRight = { yearFocusRequester.requestFocusSafe() },
                onDown = onFocusGrid
            )

            // Dropdown 4: Год
            val currentYearItem = yearsList.find { it.year == currentYear }
                ?: yearsList.firstOrNull()
                ?: YearItem("Все года", "")

            TvRezkaDropdown(
                label = "",
                options = yearsList,
                selectedOption = currentYearItem,
                onOptionSelected = { yearItem -> onYearSelected(yearItem) },
                getLabel = { it.name },
                modifier = Modifier.weight(1f),
                focusRequester = yearFocusRequester,
                onUp = navigateUpFromFilters,
                onLeft = { genreFocusRequester.requestFocusSafe() },
                onRight = { countryFocusRequester.requestFocusSafe() },
                onDown = onFocusGrid
            )

            // Dropdown 5: Страна
            val currentCountryItem = countriesList.find { it.query == currentCountry }
                ?: countriesList.firstOrNull()
                ?: CountryItem("Все страны", "")

            TvCountryDropdown(
                selectedCountry = currentCountryItem,
                countriesList = countriesList,
                onCountrySelected = onCountrySelected,
                modifier = Modifier.weight(1f),
                focusRequester = countryFocusRequester,
                onUp = navigateUpFromFilters,
                onLeft = { yearFocusRequester.requestFocusSafe() },
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
    label: String = "",
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
    var wasExpanded by remember { mutableStateOf(false) }
    val triggerRequester = focusRequester ?: remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(expanded) {
        if (wasExpanded && !expanded) {
            triggerRequester.requestFocusSafe()
        }
        wasExpanded = expanded
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
                    if (label.isNotBlank()) {
                        Text(
                            text = "$label:",
                            color = CinemaTextGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
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
                            scaleFactor = 1.0f,
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
    voiceButtonFocusRequester: FocusRequester? = null,
    onVoiceSearchClick: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSearchCommit: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    var isEditing by remember { mutableStateOf(false) }
    var hasBeenFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // При выходе из режима редактирования гарантированно восстанавливаем фокус на строке поиска
    LaunchedEffect(isEditing) {
        if (!isEditing) {
            hasBeenFocused = false
            for (attempt in 0..4) {
                delay(if (attempt == 0) 30L else 40L)
                try {
                    searchBarFocusRequester?.requestFocus()
                    break
                } catch (_: Throwable) {}
            }
        } else {
            onFocusChanged?.invoke(true)
        }
    }

    // При открытой клавиатуре по кнопке Назад пульта скрываем клавиатуру и сохраняем фокус на строке поиска
    BackHandler(enabled = isEditing) {
        isEditing = false
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
            .then(
                if (searchBarFocusRequester != null) Modifier.focusRequester(searchBarFocusRequester) else Modifier
            )
            .focusProperties {
                // Запрещаем переход фокуса ВВЕРХ с поисковой строки в боковое меню.
                up = FocusRequester.Cancel
            }
            .onFocusChanged { focusState ->
                onFocusChanged?.invoke(focusState.isFocused || isEditing)
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
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!isEditing && voiceButtonFocusRequester != null && onVoiceSearchClick != null) {
                                voiceButtonFocusRequester.requestFocusSafe()
                                true
                            } else if (!isEditing && onRight != null) {
                                onRight()
                                true
                            } else false
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!isEditing && onDown != null) {
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
                        },
                        onFocused = {
                            onFocusChanged?.invoke(true)
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
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Очистить",
                        tint = CinemaTextGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Встроенная в поисковую строку ТВ кнопка микрофона
            if (onVoiceSearchClick != null) {
                Spacer(modifier = Modifier.width(6.dp))
                var isVoiceFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .then(
                            if (isVoiceFocused) Modifier.background(CinemaPrimary.copy(alpha = 0.25f), CircleShape) else Modifier
                        )
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                        searchBarFocusRequester?.requestFocusSafe()
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                        onDown?.invoke()
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_UP -> true
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true
                                    else -> false
                                }
                            } else false
                        }
                        .tvFocusableItem(
                            onClick = {
                                com.example.ui.haptics.HapticEngine.get().perform(com.example.ui.haptics.HapticType.GENTLE_TICK)
                                onVoiceSearchClick()
                            },
                            onFocusChanged = { isVoiceFocused = it },
                            scaleFactor = 1.1f,
                            focusedBorderColor = CinemaPrimary,
                            focusedBorderWidth = 2.dp,
                            shape = CircleShape,
                            focusRequester = voiceButtonFocusRequester
                        )
                        .testTag("tv_voice_search_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Голосовой поиск",
                        tint = if (isVoiceFocused) CinemaPrimary else CinemaTextWhite.copy(alpha = 0.85f),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

/**
 * Карточка фильма для ТВ.
 * Поддерживает фокус с пульта, плавное GPU-увеличение, поднятие zIndex и обновление Hero Preview.
 * Динамически вписывается по высоте карточки для отображения целых рядов без обрезки.
 */
@Composable
private fun TvMovieCard(
    item: RezkaItem,
    index: Int,
    totalItems: Int,
    columnCount: Int,
    cardHeight: Dp = Dp.Unspecified,
    cardWidth: Dp = Dp.Unspecified,
    seriesBadgeMode: SeriesBadgeMode = RezkaService.seriesBadgeMode.value,
    isBouncing: Boolean = false,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onNavigateIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onDownAtEnd: (() -> Unit)? = null
) {
    val safeCols = if (columnCount > 0) columnCount else 4
    val isFirstColumn = index % safeCols == 0

    val isDense = columnCount >= 6 || (cardHeight != Dp.Unspecified && cardHeight.value < 140f)
    val isUltraDense = columnCount >= 8 || (cardHeight != Dp.Unspecified && cardHeight.value < 100f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (cardHeight != Dp.Unspecified) Modifier.height(cardHeight) else Modifier)
            .subtleCardBounce(
                isBouncing = isBouncing,
                shape = RoundedCornerShape(if (isUltraDense) 6.dp else if (isDense) 8.dp else 12.dp)
            )
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
                            } else {
                                if (onDownAtEnd != null) {
                                    onDownAtEnd()
                                    true
                                } else true
                            }
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
                scaleFactor = 1.0f,
                focusedBorderWidth = if (isUltraDense) 1.5.dp else if (isDense) 2.dp else 2.dp,
                shape = RoundedCornerShape(if (isUltraDense) 6.dp else if (isDense) 8.dp else 12.dp),
                focusRequester = focusRequester,
                hideBorder = isBouncing
            )
            .testTag("tv_movie_card_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = CinemaDark),
        shape = RoundedCornerShape(if (isUltraDense) 6.dp else if (isDense) 8.dp else 12.dp)
    ) {
        Column(modifier = if (cardHeight != Dp.Unspecified) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (cardHeight != Dp.Unspecified) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.aspectRatio(0.68f)
                        }
                    )
            ) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Adaptive Episode Badge (Series only)
                AdaptivePosterBadge(
                    rawText = item.rating,
                    isSeries = item.type != RezkaType.MOVIE,
                    columnsCount = columnCount,
                    cardHeight = cardHeight,
                    cardWidth = cardWidth,
                    seriesBadgeMode = seriesBadgeMode,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (isUltraDense) 3.dp else if (isDense) 5.dp else 8.dp,
                        vertical = if (isUltraDense) 2.dp else if (isDense) 3.dp else 6.dp
                    )
            ) {
                Text(
                    text = item.title,
                    color = CinemaTextWhite,
                    fontSize = if (isUltraDense) 8.5.sp else if (isDense) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isUltraDense || (cardHeight != Dp.Unspecified && cardHeight.value >= 85f)) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = item.subtitle,
                        color = CinemaTextGray,
                        fontSize = if (isUltraDense) 6.5.sp else if (isDense) 8.sp else 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TvUpdateSidebarItem(
    updateState: UpdateState,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onRight: (() -> Unit)? = null
) {
    val bgColor = when (updateState) {
        is UpdateState.ReadyToInstall -> CinemaPrimary.copy(alpha = 0.3f)
        is UpdateState.Downloading -> Color.Transparent
        is UpdateState.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
        else -> CinemaPrimary.copy(alpha = 0.15f)
    }
    val contentColor = when (updateState) {
        is UpdateState.ReadyToInstall -> CinemaPrimary
        is UpdateState.Error -> MaterialTheme.colorScheme.error
        else -> CinemaPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(bgColor, RoundedCornerShape(10.dp))
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
                scaleFactor = 1.0f,
                focusedBorderWidth = 2.dp,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center
        ) {
            if (updateState is UpdateState.Downloading) {
                CircularProgressIndicator(
                    color = CinemaPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    imageVector = when (updateState) {
                        is UpdateState.ReadyToInstall -> Icons.Default.Check
                        is UpdateState.Error -> Icons.Default.Error
                        else -> Icons.Default.SystemUpdate
                    },
                    contentDescription = "Обновление",
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (isExpanded) {
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = when (updateState) {
                        is UpdateState.UpdateAvailable -> "Обновить"
                        is UpdateState.Downloading -> {
                            val pct = (updateState.progress * 100).toInt()
                            if (pct >= 0) "Загрузка $pct%" else "Загрузка..."
                        }
                        is UpdateState.ReadyToInstall -> "Установить"
                        is UpdateState.Error -> "Ошибка"
                        else -> "Обновить"
                    },
                    color = CinemaTextWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (updateState) {
                        is UpdateState.UpdateAvailable -> updateState.latestVersion
                        is UpdateState.ReadyToInstall -> "Готово"
                        is UpdateState.Error -> "Сбросить"
                        else -> "В процессе"
                    },
                    color = CinemaTextGray,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
