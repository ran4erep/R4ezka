package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.*
import com.example.ui.util.rememberSavedLazyGridState
import com.example.ui.CatalogState
import com.example.ui.RezkaViewModel
import com.example.ui.theme.*
import com.example.ui.tv.*
import com.example.ui.haptics.bounceOverscroll
import com.example.ui.haptics.LocalHapticEngine
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType

import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.*
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(
    viewModel: RezkaViewModel,
    onNavigateToDetail: (RezkaItem) -> Unit,
    onNavigateToThematic: (String, String) -> Unit = { _, _ -> },
    onNavigateToSettings: () -> Unit = {},
    isTopScreen: Boolean = true,
    isTvMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val catalogState by viewModel.catalogState.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val currentType by viewModel.currentType.collectAsState()
    val currentSection by viewModel.currentSection.collectAsState()
    val currentGenre by viewModel.currentGenre.collectAsState()
    val genresList by viewModel.genresList.collectAsState()
    val currentYear by viewModel.currentYear.collectAsState()
    val yearsList by viewModel.yearsList.collectAsState()
    val currentCountry by viewModel.currentCountry.collectAsState()
    val countriesList by viewModel.countriesList.collectAsState()
    val collectionsState by viewModel.collectionsState.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val isEndReached by viewModel.isEndReached.collectAsState()
    val cardGridMode by viewModel.cardGridMode.collectAsState()
    val parsedCardGrid = remember(cardGridMode) { RezkaService.parseCardGrid(cardGridMode) }
    var searchInput by remember { mutableStateOf(viewModel.searchQuery) }
    val searchHistory by viewModel.searchHistory.collectAsState()
    var isSearchFocused by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isMobilePortrait = !isTvMode && !isLandscape

    var isFiltersExpanded by rememberSaveable { mutableStateOf(false) }

    val defaultType by viewModel.defaultCatalogType.collectAsState()
    val defaultSection by viewModel.defaultCatalogSection.collectAsState()

    val hasActiveFilters = currentType != defaultType ||
            currentSection != defaultSection ||
            currentGenre.isNotEmpty() ||
            currentYear.isNotEmpty() ||
            currentCountry.isNotEmpty()

    // Persistent grid state for catalog with smart scroll reset on search or filter change
    val gridState = rememberSavedLazyGridState("catalog", viewModel)

    val currentCatalogKey = remember(currentType, currentSection, currentGenre, currentYear, currentCountry, viewModel.searchQuery) {
        "${currentType.name}_${currentSection.name}_${currentGenre}_${currentYear}_${currentCountry}_${viewModel.searchQuery.trim()}"
    }
    var lastRenderedCatalogKey by rememberSaveable { mutableStateOf(currentCatalogKey) }

    LaunchedEffect(currentCatalogKey) {
        if (currentCatalogKey != lastRenderedCatalogKey) {
            lastRenderedCatalogKey = currentCatalogKey
            gridState.scrollToItem(0, 0)
            viewModel.resetScrollPosition("catalog")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.catalogScrollResetEvent.collect {
            gridState.scrollToItem(0, 0)
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val isSyncing by viewModel.isSyncing.collectAsState()

    val isKeyboardVisible = WindowInsets.isImeVisible
    LaunchedEffect(isKeyboardVisible) {
        if (!isKeyboardVisible && isSearchFocused) {
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(viewModel.searchQuery) {
        if (searchInput != viewModel.searchQuery) {
            searchInput = viewModel.searchQuery
        }
    }

    BackHandler(enabled = isTopScreen && (isSearchFocused || searchInput.isNotEmpty())) {
        if (isSearchFocused) {
            focusManager.clearFocus()
            keyboardController?.hide()
        } else {
            searchInput = ""
            viewModel.onSearchQueryChanged("")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        // ---- SEARCH BAR ----
        if (isMobilePortrait && currentType != RezkaType.COLLECTIONS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = searchInput,
                    onValueChange = {
                        searchInput = it
                        viewModel.onSearchQueryChanged(it)
                    },
                    placeholder = { Text("Поиск фильмов, сериалов, аниме...", color = CinemaMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск", tint = CinemaPrimary) },
                    trailingIcon = {
                        if (searchInput.isNotEmpty()) {
                            IconButton(onClick = {
                                HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                searchInput = ""
                                viewModel.onSearchQueryChanged("")
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Очистить", tint = CinemaTextGray)
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            viewModel.commitSearchQuery(searchInput)
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = CinemaDark,
                        unfocusedContainerColor = CinemaDark,
                        focusedTextColor = CinemaTextWhite,
                        unfocusedTextColor = CinemaTextWhite,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .onFocusChanged { isSearchFocused = it.isFocused }
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown &&
                                (keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                 keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)
                            ) {
                                viewModel.commitSearchQuery(searchInput)
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                true
                            } else false
                        }
                        .testTag("catalog_search_bar")
                )

                // Кнопка скрытия/раскрытия выпадающих списков чисто для телефонов в вертикальной ориентации
                // Умный клик: одиночное нажатие переключает видимость, двойное сбрасывает фильтры без открытия/закрытия
                val filterClickScope = rememberCoroutineScope()
                var singleClickJob by remember { mutableStateOf<Job?>(null) }

                Surface(
                    onClick = {
                        val haptic = com.example.ui.haptics.HapticEngine.get()
                        haptic.perform(com.example.ui.haptics.HapticType.GENTLE_TICK)
                        val activeJob = singleClickJob
                        if (activeJob != null && activeJob.isActive) {
                            // Второе нажатие пришло в пределах таймаута: отменяем одиночный клик и выполняем сброс!
                            activeJob.cancel()
                            singleClickJob = null
                            viewModel.resetCatalogFilters()
                        } else {
                            // Первое нажатие: запускаем отложенный таймер одиночного клика
                            singleClickJob = filterClickScope.launch {
                                delay(280L)
                                isFiltersExpanded = !isFiltersExpanded
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaDark,
                    border = BorderStroke(1.dp, CinemaBorder),
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("catalog_filter_toggle_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = if (isFiltersExpanded) "Скрыть фильтры" else "Показать фильтры",
                            tint = CinemaTextWhite,
                            modifier = Modifier.size(24.dp)
                        )
                        // Акцентная точка активных фильтров (всегда отображается, даже если фильтры открыты)
                        if (hasActiveFilters) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 9.dp, end = 9.dp)
                                    .size(8.dp)
                                    .background(CinemaPrimary, CircleShape)
                            )
                        }
                    }
                }
            }
        } else {
            // Для телевизоров и ландшафтной ориентации исходное поле во всю ширину
            TextField(
                value = searchInput,
                onValueChange = {
                    searchInput = it
                    viewModel.onSearchQueryChanged(it)
                },
                placeholder = { Text("Поиск фильмов, сериалов, аниме...", color = CinemaMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск", tint = CinemaPrimary) },
                trailingIcon = {
                    if (searchInput.isNotEmpty()) {
                        IconButton(onClick = {
                            HapticEngine.get().perform(HapticType.GENTLE_TICK)
                            searchInput = ""
                            viewModel.onSearchQueryChanged("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить", tint = CinemaTextGray)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        viewModel.commitSearchQuery(searchInput)
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CinemaDark,
                    unfocusedContainerColor = CinemaDark,
                    focusedTextColor = CinemaTextWhite,
                    unfocusedTextColor = CinemaTextWhite,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp)
                    .onFocusChanged { isSearchFocused = it.isFocused }
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown &&
                            (keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                             keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)
                        ) {
                            viewModel.commitSearchQuery(searchInput)
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            true
                        } else false
                    }
                    .testTag("catalog_search_bar")
            )
        }

        // ---- ВСПЛЫВАЮЩАЯ ИСТОРИЯ ПОИСКА (ПОСЛЕДНИЕ 5 ЗАПРОСОВ) ----
        AnimatedVisibility(
            visible = searchInput.isEmpty() && isSearchFocused && searchHistory.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CinemaDark),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CinemaBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("search_history_container")
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = CinemaPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Недавние запросы",
                                color = CinemaTextGray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Очистить",
                            color = CinemaPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                    viewModel.clearSearchHistory()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("clear_search_history_btn")
                        )
                    }

                    searchHistory.take(5).forEach { query ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    HapticEngine.get().perform(HapticType.SELECTION)
                                    searchInput = query
                                    viewModel.onSearchQueryChanged(query)
                                    viewModel.commitSearchQuery(query)
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("search_history_item_$query"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = CinemaMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = query,
                                color = CinemaTextWhite,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = {
                                    HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                    viewModel.removeSearchQueryFromHistory(query)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Удалить из истории",
                                    tint = CinemaTextGray,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- FILTERS DROPDOWNS ----
        val categories = listOf(
            RezkaType.MOVIE to "Фильмы",
            RezkaType.SERIES to "Сериалы",
            RezkaType.ANIME to "Аниме",
            RezkaType.CARTOON to "Мультики",
            RezkaType.COLLECTIONS to "Подборки"
        )
        val currentCategoryPair = categories.find { it.first == currentType } ?: categories[0]

        if (currentType == RezkaType.COLLECTIONS) {
            // Для подборок: список категорий ВСЕГДА активен и виден на телефоне и любом экране
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RezkaDropdown(
                    label = "Категория",
                    options = categories,
                    selectedOption = currentCategoryPair,
                    onOptionSelected = { pair ->
                        searchInput = ""
                        viewModel.loadCatalog(type = pair.first, forceRefresh = true)
                    },
                    getLabel = { it.second },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            AnimatedVisibility(
                visible = !isMobilePortrait || isFiltersExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                // Dropdown 2: Разделы
                val sections = listOf(
                    SectionType.LATEST,
                    SectionType.POPULAR,
                    SectionType.WATCHING,
                    SectionType.AWAITING
                )

                // Dropdown 3: Жанры
                val currentGenreItem = genresList.find { it.slug == currentGenre } ?: genresList.firstOrNull() ?: GenreItem("Без жанра", "")

                // Dropdown 4: Года (динамически спарсенные с сайта, рядом с жанром)
                val currentYearItem = yearsList.find { it.year == currentYear } ?: yearsList.firstOrNull() ?: YearItem("Все года", "")

                // Dropdown 5: Страна
                val currentCountryItem = countriesList.find { it.query == currentCountry } ?: countriesList.firstOrNull() ?: CountryItem("Все страны", "")

                if (isLandscape) {
                    // Широкий экран: 5 фильтров в один аккуратный ряд
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RezkaDropdown(
                            label = "Категория",
                            options = categories,
                            selectedOption = currentCategoryPair,
                            onOptionSelected = { pair ->
                                searchInput = ""
                                viewModel.loadCatalog(type = pair.first, genre = "", year = "", forceRefresh = true)
                            },
                            getLabel = { it.second },
                            modifier = Modifier.weight(1f)
                        )

                        RezkaDropdown(
                            label = "Раздел",
                            options = sections,
                            selectedOption = currentSection,
                            onOptionSelected = { section ->
                                searchInput = ""
                                viewModel.loadCatalog(section = section, forceRefresh = true)
                            },
                            getLabel = { it.getDisplayName() },
                            modifier = Modifier.weight(1f)
                        )

                        RezkaDropdown(
                            label = "Жанр",
                            options = genresList,
                            selectedOption = currentGenreItem,
                            onOptionSelected = { genreItem ->
                                searchInput = ""
                                viewModel.loadCatalog(genre = genreItem.slug, forceRefresh = true)
                            },
                            getLabel = { it.name },
                            modifier = Modifier.weight(1f)
                        )

                        RezkaDropdown(
                            label = "Год",
                            options = yearsList,
                            selectedOption = currentYearItem,
                            onOptionSelected = { yearItem ->
                                searchInput = ""
                                viewModel.loadCatalog(year = yearItem.year, forceRefresh = true)
                            },
                            getLabel = { it.name },
                            modifier = Modifier.weight(1f)
                        )

                        RezkaDropdown(
                            label = "Страна",
                            options = countriesList,
                            selectedOption = currentCountryItem,
                            onOptionSelected = { countryItem ->
                                viewModel.setCountry(countryItem.query)
                            },
                            getLabel = { it.name },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    // Мобильный вертикальный экран: 2 строки фильтров (жанры, года и страны объединены в одну строку)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Строка 1: Категория и Раздел
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RezkaDropdown(
                                label = "Категория",
                                options = categories,
                                selectedOption = currentCategoryPair,
                                onOptionSelected = { pair ->
                                    searchInput = ""
                                    viewModel.loadCatalog(type = pair.first, genre = "", year = "", forceRefresh = true)
                                },
                                getLabel = { it.second },
                                modifier = Modifier.weight(1f)
                            )

                            RezkaDropdown(
                                label = "Раздел",
                                options = sections,
                                selectedOption = currentSection,
                                onOptionSelected = { section ->
                                    searchInput = ""
                                    viewModel.loadCatalog(section = section, forceRefresh = true)
                                },
                                getLabel = { it.getDisplayName() },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Строка 2: Жанры, Года и Страны в одну строку
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RezkaDropdown(
                                label = "Жанр",
                                options = genresList,
                                selectedOption = currentGenreItem,
                                onOptionSelected = { genreItem ->
                                    searchInput = ""
                                    viewModel.loadCatalog(genre = genreItem.slug, forceRefresh = true)
                                },
                                getLabel = { it.name },
                                modifier = Modifier.weight(1f)
                            )

                            RezkaDropdown(
                                label = "Год",
                                options = yearsList,
                                selectedOption = currentYearItem,
                                onOptionSelected = { yearItem ->
                                    searchInput = ""
                                    viewModel.loadCatalog(year = yearItem.year, forceRefresh = true)
                                },
                                getLabel = { it.name },
                                modifier = Modifier.weight(1f)
                            )

                            RezkaDropdown(
                                label = "Страна",
                                options = countriesList,
                                selectedOption = currentCountryItem,
                                onOptionSelected = { countryItem ->
                                    viewModel.setCountry(countryItem.query)
                                },
                                getLabel = { it.name },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // ---- CATALOG / COLLECTIONS GRID / CONTENT ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (currentType == RezkaType.COLLECTIONS) {
                // ---- ВЫВОД ПОДБОРОК (БЕЗ ПАГИНАЦИИ) ----
                when (val cState = collectionsState) {
                    is CollectionsState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(48.dp))
                        }
                    }
                    is CollectionsState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = CinemaPrimary, modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(cState.message, color = CinemaTextWhite, textAlign = TextAlign.Center, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(16.dp))
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
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.SearchOff, contentDescription = null, tint = CinemaMuted, modifier = Modifier.size(64.dp))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (searchInput.isNotBlank()) "Подборок по вашему запросу не найдено" else "Ничего не можем найти по данному запросу",
                                    color = CinemaTextGray,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            val configuration = LocalConfiguration.current
                            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                            val columns = if (isLandscape) 3 else 2

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (isLandscape) 16.dp else 80.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .bounceOverscroll(androidx.compose.foundation.gestures.Orientation.Vertical)
                                    .testTag("collections_grid")
                            ) {
                                items(
                                    items = cState.items,
                                    key = { it.id }
                                ) { collectionItem ->
                                    CollectionCard(
                                        item = collectionItem,
                                        onClick = {
                                            viewModel.commitSearchQuery(searchInput)
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            onNavigateToThematic(collectionItem.title, collectionItem.url)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // ---- СТАНДАРТНЫЙ КАТАЛОГ ФИЛЬМОВ/СЕРИАЛОВ ----
                when (val state = catalogState) {
                    is CatalogState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(48.dp))
                        }
                    }
                    is CatalogState.Error -> {
                        if (!isOnline) {
                            LaunchedEffect(Unit) {
                                viewModel.loadOfflineCatalog()
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = CinemaPrimary, modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (!isOnline) "Нет подключения к интернету\nОтображается оффлайн библиотека" else state.message,
                                color = CinemaTextWhite,
                                textAlign = TextAlign.Center,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                        viewModel.loadCatalog(forceRefresh = true)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.tvFocusableItem(onClick = { viewModel.loadCatalog(forceRefresh = true) })
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Повторить")
                                }
                                if (!isOnline) {
                                    Button(
                                        onClick = {
                                            HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                            viewModel.loadOfflineCatalog(forceRefresh = true)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CinemaCard),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.tvFocusableItem(onClick = { viewModel.loadOfflineCatalog(forceRefresh = true) })
                                    ) {
                                        Text("Оффлайн")
                                    }
                                }
                            }
                        }
                    }
                    is CatalogState.Success -> {
                        val displayedItems = remember(state.items, currentCountry) {
                            if (currentCountry.isEmpty()) {
                                state.items
                            } else {
                                state.items.filter { it.matchesCountry(currentCountry) }
                            }
                        }

                        // Если выбран фильтр по стране и найдено мало карточек, автоматически подгружаем следующую страницу
                        LaunchedEffect(displayedItems.size, currentCountry, isEndReached, isLoadingMore) {
                            if (isOnline && currentCountry.isNotEmpty() && displayedItems.size < 20 && !isEndReached && !isLoadingMore && searchInput.isEmpty()) {
                                viewModel.loadNextPage()
                            }
                        }

                        if (displayedItems.isEmpty()) {
                            if (!isOnline) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = CinemaPrimary, modifier = Modifier.size(64.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = if (searchInput.isNotBlank()) "В оффлайн библиотеке ничего не найдено по запросу \"$searchInput\"" else "Оффлайн библиотека пуста",
                                        color = CinemaTextWhite,
                                        textAlign = TextAlign.Center,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Сохраняйте фильмы и серии в оффлайн библиотеку со страницы просмотра, чтобы смотреть их без интернета.",
                                        color = CinemaTextGray,
                                        textAlign = TextAlign.Center,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = {
                                                HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                                viewModel.loadCatalog(forceRefresh = true)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.tvFocusableItem(onClick = { viewModel.loadCatalog(forceRefresh = true) })
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Проверить сеть")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                HapticEngine.get().perform(HapticType.GENTLE_TICK)
                                                onNavigateToSettings()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.6f)),
                                            modifier = Modifier.tvFocusableItem(onClick = onNavigateToSettings)
                                        ) {
                                            Text("Настройки", color = CinemaTextWhite)
                                        }
                                    }
                                }
                            } else if (currentCountry.isNotEmpty() && !isEndReached) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(
                                            color = CinemaPrimary,
                                            modifier = Modifier.size(36.dp),
                                            strokeWidth = 3.dp
                                        )
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Text(
                                            text = "Поиск фильмов по выбранной стране...",
                                            color = CinemaTextGray,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.SearchOff, contentDescription = null, tint = CinemaMuted, modifier = Modifier.size(64.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = if (currentCountry.isNotEmpty()) "Фильмы по данной стране не найдены" else "Ничего не можем найти по данному запросу",
                                        color = CinemaTextGray,
                                        fontSize = 15.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 22.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        } else {
                            // Ultra-efficient scroll observer via derivedStateOf
                            val shouldLoadMore by remember {
                                derivedStateOf {
                                    val totalItems = displayedItems.size
                                    val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                    if (currentCountry.isNotEmpty()) {
                                        totalItems > 0 && lastVisibleIndex >= totalItems - 6
                                    } else {
                                        totalItems >= 12 && lastVisibleIndex >= totalItems - 4 && gridState.canScrollForward
                                    }
                                }
                            }

                            LaunchedEffect(shouldLoadMore, isLoadingMore, isEndReached) {
                                if (shouldLoadMore && !isLoadingMore && !isEndReached && searchInput.isEmpty()) {
                                    viewModel.loadNextPage()
                                }
                            }

                            // Засчитываем запрос в историю поиска при начале скролла результатов
                            LaunchedEffect(gridState.isScrollInProgress) {
                                if (gridState.isScrollInProgress && searchInput.isNotBlank()) {
                                    viewModel.commitSearchQuery(searchInput)
                                }
                            }

                            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val coroutineScope = rememberCoroutineScope()
                                val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
                                fun getFocusRequesterForIndex(idx: Int): FocusRequester {
                                    return itemFocusRequesters.getOrPut(idx) { FocusRequester() }
                                }

                                val resolvedGrid = remember(cardGridMode, maxWidth, maxHeight, isLandscape) {
                                    com.example.ui.tv.CardGridEngine.calculate(
                                        cardGridMode = cardGridMode,
                                        availableWidth = maxWidth - 32.dp,
                                        availableHeight = maxHeight - 12.dp,
                                        isLandscapeOrTv = isLandscape
                                    )
                                }

                                val columnsCount = resolvedGrid.columns
                                val cardHeight = resolvedGrid.cardHeight

                                 LazyVerticalGrid(
                                    columns = GridCells.Fixed(columnsCount),
                                    state = gridState,
                                    contentPadding = PaddingValues(top = 10.dp, start = 16.dp, end = 16.dp, bottom = if (isLandscape) 16.dp else 80.dp),
                                    horizontalArrangement = Arrangement.spacedBy(resolvedGrid.horizontalSpacing),
                                    verticalArrangement = Arrangement.spacedBy(resolvedGrid.verticalSpacing),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .bounceOverscroll(androidx.compose.foundation.gestures.Orientation.Vertical)
                                        .dpadScrollable(gridState)
                                        .testTag("catalog_items_grid")
                                ) {
                                    itemsIndexed(
                                        items = displayedItems,
                                        key = { _, item -> item.id }
                                    ) { index, item ->
                                        val navigateToItem: (Int) -> Unit = { targetIndex ->
                                            val total = displayedItems.size
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

                                        RezkaItemCard(
                                            item = item,
                                            index = index,
                                            totalItems = displayedItems.size,
                                            columnsCount = columnsCount,
                                            cardHeight = cardHeight,
                                            onNavigateIndex = navigateToItem,
                                            focusRequester = getFocusRequesterForIndex(index),
                                            onClick = {
                                                viewModel.commitSearchQuery(searchInput)
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                onNavigateToDetail(item)
                                            }
                                        )
                                    }

                                    if (isLoadingMore && displayedItems.size >= 8) {
                                        item(span = { GridItemSpan(maxLineSpan) }) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    color = CinemaPrimary,
                                                    modifier = Modifier.size(32.dp),
                                                    strokeWidth = 3.dp
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
    }
}

@Composable
fun RezkaItemCard(
    item: RezkaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    columnsCount: Int = 2,
    cardHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    showMovieRating: Boolean = true,
    index: Int = 0,
    totalItems: Int = 1,
    focusRequester: FocusRequester? = null,
    onNavigateIndex: ((Int) -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null
) {
    val bottomFadeBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
            startY = 100f
        )
    }

    val safeCols = if (columnsCount > 0) columnsCount else 2
    val isFirstColumn = index % safeCols == 0

    val isDense = columnsCount >= 5 || (cardHeight != androidx.compose.ui.unit.Dp.Unspecified && cardHeight.value < 140f)
    val isUltraDense = columnsCount >= 8 || (cardHeight != androidx.compose.ui.unit.Dp.Unspecified && cardHeight.value < 100f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (cardHeight != androidx.compose.ui.unit.Dp.Unspecified) Modifier.height(cardHeight) else Modifier)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            val targetIndex = index + safeCols
                            if (onNavigateIndex != null) {
                                if (targetIndex < totalItems) {
                                    onNavigateIndex(targetIndex)
                                    true
                                } else if (index < totalItems - 1) {
                                    onNavigateIndex(totalItems - 1)
                                    true
                                } else true
                            } else false
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            if (index >= safeCols) {
                                if (onNavigateIndex != null) {
                                    onNavigateIndex(index - safeCols)
                                    true
                                } else false
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
                                if (onNavigateIndex != null) {
                                    onNavigateIndex(index - 1)
                                    true
                                } else false
                            }
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if ((index + 1) % safeCols == 0 || index == totalItems - 1) {
                                true
                            } else {
                                if (onNavigateIndex != null) {
                                    onNavigateIndex(index + 1)
                                    true
                                } else false
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
                shape = RoundedCornerShape(if (isUltraDense) 6.dp else if (isDense) 8.dp else 12.dp),
                focusRequester = focusRequester
            )
            .testTag("movie_card_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = CinemaDark),
        shape = RoundedCornerShape(if (isUltraDense) 6.dp else if (isDense) 8.dp else 12.dp)
    ) {
        Column(modifier = if (cardHeight != androidx.compose.ui.unit.Dp.Unspecified) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (cardHeight != androidx.compose.ui.unit.Dp.Unspecified) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.aspectRatio(0.68f) // 2:3 Cinematic Poster ratio
                        }
                    )
            ) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Bottom fade overlay to blend poster with black text block
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bottomFadeBrush)
                )

                // Rating / Episode Badge
                val shouldShowBadge = item.rating.isNotEmpty() && (showMovieRating || item.type != RezkaType.MOVIE)
                if (shouldShowBadge) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(if (isUltraDense) 2.dp else if (isDense) 4.dp else 8.dp)
                            .background(CinemaPrimary, RoundedCornerShape(if (isUltraDense) 3.dp else if (isDense) 4.dp else 6.dp))
                            .padding(
                                horizontal = if (isUltraDense) 3.dp else if (isDense) 5.dp else 8.dp,
                                vertical = if (isUltraDense) 1.dp else if (isDense) 2.dp else 4.dp
                            )
                    ) {
                        Text(
                            text = item.rating,
                            color = CinemaTextWhite,
                            fontSize = if (isUltraDense) 7.sp else if (isDense) 9.sp else 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Info Block
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (isUltraDense) 3.dp else if (isDense) 5.dp else 10.dp,
                        vertical = if (isUltraDense) 2.dp else if (isDense) 4.dp else 8.dp
                    )
            ) {
                Text(
                    text = item.title,
                    color = CinemaTextWhite,
                    fontSize = if (isUltraDense) 8.5.sp else if (columnsCount >= 7) 10.sp else if (isDense) 11.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isUltraDense || (cardHeight != androidx.compose.ui.unit.Dp.Unspecified && cardHeight.value >= 85f)) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.subtitle,
                        color = CinemaTextGray,
                        fontSize = if (isUltraDense) 6.5.sp else if (columnsCount >= 7) 8.sp else if (isDense) 9.sp else 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun <T> RezkaDropdown(
    label: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    getLabel: (T) -> String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticEngine.current

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(CinemaDark)
                .clickable {
                    haptic.perform(HapticType.SELECTION)
                    expanded = true
                }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = getLabel(selectedOption),
                color = CinemaTextWhite,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = CinemaPrimary,
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(CinemaDark)
                .widthIn(max = 240.dp)
                .heightIn(max = 280.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = getLabel(option),
                            color = if (option == selectedOption) CinemaPrimary else CinemaTextWhite,
                            fontSize = 12.sp,
                            fontWeight = if (option == selectedOption) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    onClick = {
                        haptic.perform(HapticType.SELECTION)
                        onOptionSelected(option)
                        expanded = false
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = CinemaTextWhite
                    )
                )
            }
        }
    }
}

@Composable
fun CollectionCard(
    item: CollectionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    totalItems: Int = 1,
    columnCount: Int = 3,
    onNavigateIndex: ((Int) -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    val bottomFadeBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)),
            startY = 50f
        )
    }

    val safeCols = if (columnCount > 0) columnCount else 3
    val isFirstColumn = index % safeCols == 0

    Card(
        modifier = modifier
            .fillMaxWidth()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            val targetIndex = index + safeCols
                            if (onNavigateIndex != null) {
                                if (targetIndex < totalItems) {
                                    onNavigateIndex(targetIndex)
                                    true
                                } else if (index < totalItems - 1) {
                                    onNavigateIndex(totalItems - 1)
                                    true
                                } else true
                            } else false
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            if (index >= safeCols) {
                                if (onNavigateIndex != null) {
                                    onNavigateIndex(index - safeCols)
                                    true
                                } else false
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
                                if (onNavigateIndex != null) {
                                    onNavigateIndex(index - 1)
                                    true
                                } else false
                            }
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if ((index + 1) % safeCols == 0 || index == totalItems - 1) {
                                true
                            } else {
                                if (onNavigateIndex != null) {
                                    onNavigateIndex(index + 1)
                                    true
                                } else false
                            }
                        }
                        else -> false
                    }
                } else false
            }
            .tvFocusableItem(onClick = onClick, scaleFactor = 1.04f, shape = RoundedCornerShape(12.dp), focusRequester = focusRequester)
            .testTag("collection_card_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = CinemaDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.65f)
        ) {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Bottom gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bottomFadeBrush)
            )

            // Badge count (e.g., "74 видео")
            if (item.count.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(CinemaPrimary, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${item.count} видео",
                        color = CinemaTextWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Title
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = item.title,
                    color = CinemaTextWhite,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
