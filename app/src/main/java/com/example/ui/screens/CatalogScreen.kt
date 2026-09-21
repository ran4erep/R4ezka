package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(
    viewModel: RezkaViewModel,
    onNavigateToDetail: (RezkaItem) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    isTopScreen: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val catalogState by viewModel.catalogState.collectAsState()
    val currentType by viewModel.currentType.collectAsState()
    val currentSection by viewModel.currentSection.collectAsState()
    val currentGenre by viewModel.currentGenre.collectAsState()
    val genresList by viewModel.genresList.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val isEndReached by viewModel.isEndReached.collectAsState()
    val cardGridMode by viewModel.cardGridMode.collectAsState()
    val parsedCardGrid = remember(cardGridMode) { RezkaService.parseCardGrid(cardGridMode) }
    var searchInput by remember { mutableStateOf(viewModel.searchQuery) }
    val searchHistory by viewModel.searchHistory.collectAsState()
    var isSearchFocused by remember { mutableStateOf(false) }

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
                                .clickable { viewModel.clearSearchHistory() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("clear_search_history_btn")
                        )
                    }

                    searchHistory.take(5).forEach { query ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
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
                                onClick = { viewModel.removeSearchQueryFromHistory(query) },
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

        // ---- FILTERS DROPDOWNS (Category, Section, Genre) ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Dropdown 1: Категории
            val categories = listOf(
                RezkaType.MOVIE to "Фильмы",
                RezkaType.SERIES to "Сериалы",
                RezkaType.ANIME to "Аниме",
                RezkaType.CARTOON to "Мультики"
            )
            val currentCategoryPair = categories.find { it.first == currentType } ?: categories[0]

            RezkaDropdown(
                label = "Категория",
                options = categories,
                selectedOption = currentCategoryPair,
                onOptionSelected = { pair ->
                    searchInput = ""
                    viewModel.loadCatalog(type = pair.first, genre = "", forceRefresh = true)
                },
                getLabel = { it.second },
                modifier = Modifier.weight(1f)
            )

            // Dropdown 2: Разделы
            val sections = listOf(
                SectionType.LATEST,
                SectionType.POPULAR,
                SectionType.WATCHING,
                SectionType.AWAITING
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

            // Dropdown 3: Жанры
            val currentGenreItem = genresList.find { it.slug == currentGenre } ?: genresList.firstOrNull() ?: GenreItem("Без жанра", "")

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
        }

        // ---- CATALOG GRID / CONTENT ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (val state = catalogState) {
                is CatalogState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(48.dp))
                    }
                }
                is CatalogState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = CinemaPrimary, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(state.message, color = CinemaTextWhite, textAlign = TextAlign.Center, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.loadCatalog(forceRefresh = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary)
                        ) {
                            Text("Повторить")
                        }
                    }
                }
                is CatalogState.Success -> {
                    if (state.items.isEmpty()) {
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
                                text = if (searchInput.isNotBlank()) "Нам не удалось ничего найти.\nМожет стоит изменить поисковый запрос?" else "Ничего не найдено",
                                color = CinemaTextGray,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        // High-performance vertical grid with optimized pagination and saved scroll state
                        val gridState = rememberSavedLazyGridState("catalog", viewModel)

                        // Ultra-efficient scroll observer via derivedStateOf
                        val shouldLoadMore by remember {
                            derivedStateOf {
                                val totalItems = gridState.layoutInfo.totalItemsCount
                                val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                totalItems > 0 && lastVisibleIndex >= totalItems - 4
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

                        val configuration = LocalConfiguration.current
                        val columnsCount = remember(parsedCardGrid, configuration.orientation, configuration.screenWidthDp) {
                            if (parsedCardGrid != null) {
                                parsedCardGrid.columns
                            } else {
                                if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                    (configuration.screenWidthDp / 150).coerceIn(3, 8)
                                } else {
                                    2
                                }
                            }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columnsCount),
                            state = gridState,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (columnsCount >= 6) 8.dp else 14.dp),
                            verticalArrangement = Arrangement.spacedBy(if (columnsCount >= 6) 10.dp else 16.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .dpadScrollable(gridState)
                                .testTag("catalog_items_grid")
                        ) {
                            items(
                                items = state.items,
                                key = { it.id }
                            ) { item ->
                                RezkaItemCard(
                                    item = item,
                                    columnsCount = columnsCount,
                                    onClick = {
                                        viewModel.commitSearchQuery(searchInput)
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        onNavigateToDetail(item)
                                    }
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

@Composable
fun RezkaItemCard(
    item: RezkaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    columnsCount: Int = 2
) {
    val bottomFadeBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
            startY = 100f
        )
    }

    val isDense = columnsCount >= 5

    Card(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusableItem(onClick = onClick, scaleFactor = 1.05f, shape = RoundedCornerShape(if (isDense) 8.dp else 12.dp))
            .testTag("movie_card_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = CinemaDark),
        shape = RoundedCornerShape(if (isDense) 8.dp else 12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f) // 2:3 Cinematic Poster ratio
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
                if (item.rating.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(if (isDense) 4.dp else 8.dp)
                            .background(CinemaPrimary, RoundedCornerShape(if (isDense) 4.dp else 6.dp))
                            .padding(horizontal = if (isDense) 5.dp else 8.dp, vertical = if (isDense) 2.dp else 4.dp)
                    ) {
                        Text(
                            text = item.rating,
                            color = CinemaTextWhite,
                            fontSize = if (isDense) 9.sp else 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Info Block
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (isDense) 6.dp else 10.dp)
            ) {
                Text(
                    text = item.title,
                    color = CinemaTextWhite,
                    fontSize = if (columnsCount >= 7) 10.sp else if (isDense) 11.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    color = CinemaTextGray,
                    fontSize = if (columnsCount >= 7) 8.sp else if (isDense) 9.sp else 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(CinemaDark)
                .clickable { expanded = true }
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
