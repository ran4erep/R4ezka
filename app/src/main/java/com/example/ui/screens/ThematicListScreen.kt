package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import android.content.res.Configuration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.RezkaItem
import com.example.data.RezkaService
import com.example.data.RezkaType
import com.example.data.SectionType
import com.example.ui.theme.*
import com.example.ui.tv.dpadScrollable
import com.example.ui.haptics.bounceOverscroll
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import com.example.ui.tv.requestFocusSafe
import com.example.ui.tv.tvFocusableItem
import com.example.ui.util.rememberSavedLazyGridState
import kotlinx.coroutines.launch

sealed interface ThematicState {
    object Loading : ThematicState
    data class Success(val items: List<RezkaItem>) : ThematicState
    data class Error(val message: String) : ThematicState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThematicListScreen(
    title: String,
    url: String,
    onBack: () -> Unit,
    onNavigateToDetail: (RezkaItem) -> Unit,
    modifier: Modifier = Modifier,
    isTvMode: Boolean = false,
    viewModel: com.example.ui.RezkaViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val coroutineScope = rememberCoroutineScope()
    var state by remember(url) { mutableStateOf<ThematicState>(ThematicState.Loading) }
    var currentPage by remember(url) { mutableStateOf(1) }
    var isLoadingMore by remember(url) { mutableStateOf(false) }
    var isEndReached by remember(url) { mutableStateOf(false) }
    val loadedItems = remember(url) { mutableStateListOf<RezkaItem>() }

    val isCollection = remember(url) { url.contains("/collections/") }

    val sections = remember {
        listOf(
            SectionType.POPULAR,
            SectionType.LATEST,
            SectionType.AWAITING,
            SectionType.WATCHING
        )
    }
    var selectedSection by remember(url) {
        val initial = if (url.contains("filter=last")) SectionType.LATEST
        else if (url.contains("filter=soon")) SectionType.AWAITING
        else if (url.contains("filter=watching")) SectionType.WATCHING
        else SectionType.POPULAR
        mutableStateOf(initial)
    }

    val effectiveUrl = remember(url, selectedSection, isCollection) {
        if (!isCollection) {
            url
        } else {
            val filterParam = when (selectedSection) {
                SectionType.POPULAR -> "popular"
                SectionType.LATEST -> "last"
                SectionType.AWAITING -> "soon"
                SectionType.WATCHING -> "watching"
            }
            val baseWithoutParams = url.substringBefore("?")
            val existingParams = if (url.contains("?")) {
                url.substringAfter("?").split("&")
                    .filterNot { it.startsWith("filter=") || it.isBlank() }
                    .joinToString("&")
            } else ""

            val normalizedBase = if (baseWithoutParams.endsWith("/")) baseWithoutParams else "$baseWithoutParams/"
            val paramsList = mutableListOf("filter=$filterParam")
            if (existingParams.isNotEmpty()) {
                paramsList.add(existingParams)
            }
            "$normalizedBase?" + paramsList.joinToString("&")
        }
    }

    // Load items when effectiveUrl changes
    LaunchedEffect(effectiveUrl) {
        state = ThematicState.Loading
        currentPage = 1
        isEndReached = false
        isLoadingMore = false
        loadedItems.clear()
        try {
            val items = RezkaService.getCustomCatalog(effectiveUrl, 1)
            loadedItems.clear()
            loadedItems.addAll(items)
            isEndReached = items.size < 32
            state = ThematicState.Success(loadedItems)
        } catch (e: Exception) {
            if (e.message?.contains("404") == true || e.message?.contains("не найден", ignoreCase = true) == true) {
                loadedItems.clear()
                state = ThematicState.Success(emptyList())
            } else {
                state = ThematicState.Error(e.message ?: "Ошибка загрузки")
            }
        }
    }

    // Load next page
    fun loadNextPage() {
        if (isLoadingMore || isEndReached || state !is ThematicState.Success) return
        isLoadingMore = true
        coroutineScope.launch {
            try {
                val nextPage = currentPage + 1
                val items = RezkaService.getCustomCatalog(effectiveUrl, nextPage)
                val newUniqueItems = items.filterNot { newItem -> loadedItems.any { it.id == newItem.id } }
                if (newUniqueItems.isEmpty() || items.size < 32) {
                    isEndReached = true
                }
                if (newUniqueItems.isNotEmpty()) {
                    loadedItems.addAll(newUniqueItems)
                    currentPage = nextPage
                }
            } catch (_: Exception) {
                isEndReached = true
            } finally {
                isLoadingMore = false
            }
        }
    }

    BackHandler(onBack = onBack)

    val backFocusRequester = remember { FocusRequester() }
    val sectionFocusRequester = remember { FocusRequester() }

    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun getFocusRequesterForIndex(index: Int): FocusRequester {
        return itemFocusRequesters.getOrPut(index) { FocusRequester() }
    }

    LaunchedEffect(Unit) {
        if (isTvMode) {
            if (isCollection) {
                sectionFocusRequester.requestFocusSafe()
            } else {
                backFocusRequester.requestFocusSafe()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        // ---- TOP BAR ----
        TopAppBar(
            title = {
                Text(
                    text = title,
                    color = CinemaTextWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            },
            navigationIcon = {
                if (isTvMode) {
                    Surface(
                        color = CinemaCard,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .padding(start = 12.dp, end = 4.dp)
                            .focusRequester(backFocusRequester)
                            .tvFocusableItem(
                                onClick = onBack,
                                scaleFactor = 1.08f,
                                shape = RoundedCornerShape(8.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = CinemaTextWhite,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Назад",
                                color = CinemaTextWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    IconButton(onClick = {
                        HapticEngine.get().perform(HapticType.GENTLE_TICK)
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = CinemaTextWhite
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = CinemaDark,
                titleContentColor = CinemaTextWhite
            )
        )

        // ---- SECTION FILTERS (for collections) ----
        if (isCollection) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isTvMode) {
                    com.example.ui.tv.TvRezkaDropdown(
                        label = "Сортировка",
                        options = sections,
                        selectedOption = selectedSection,
                        onOptionSelected = { newSection ->
                            if (selectedSection != newSection) {
                                selectedSection = newSection
                            }
                        },
                        getLabel = { it.getDisplayName() },
                        modifier = Modifier.weight(1f),
                        focusRequester = sectionFocusRequester,
                        onUp = { backFocusRequester.requestFocusSafe() },
                        onLeft = { backFocusRequester.requestFocusSafe() },
                        onDown = {
                            coroutineScope.launch {
                                try {
                                    getFocusRequesterForIndex(0).requestFocusSafe()
                                } catch (_: Exception) {}
                            }
                        }
                    )
                } else {
                    RezkaDropdown(
                        label = "Сортировка",
                        options = sections,
                        selectedOption = selectedSection,
                        onOptionSelected = { newSection ->
                            if (selectedSection != newSection) {
                                selectedSection = newSection
                            }
                        },
                        getLabel = { it.getDisplayName() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ---- CONTENT ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (val currentState = state) {
                is ThematicState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(48.dp))
                    }
                }
                is ThematicState.Error -> {
                    val isNothingFound = currentState.message.contains("404") ||
                            currentState.message.contains("не найден", ignoreCase = true) ||
                            currentState.message.contains("Ничего не можем найти", ignoreCase = true)

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = if (isNothingFound) Icons.Default.SearchOff else Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = if (isNothingFound) CinemaMuted else CinemaPrimary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (isNothingFound) "Ничего не можем найти по данному запросу" else currentState.message,
                            color = if (isNothingFound) CinemaTextGray else CinemaTextWhite,
                            textAlign = TextAlign.Center,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (!isNothingFound) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    HapticEngine.get().perform(HapticType.SOFT_CLICK)
                                    coroutineScope.launch {
                                        state = ThematicState.Loading
                                        try {
                                            val items = RezkaService.getCustomCatalog(effectiveUrl, 1)
                                            loadedItems.clear()
                                            loadedItems.addAll(items)
                                            state = ThematicState.Success(loadedItems)
                                        } catch (e: Exception) {
                                            state = ThematicState.Error(e.message ?: "Ошибка загрузки")
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary)
                            ) {
                                Text("Повторить")
                            }
                        }
                    }
                }
                is ThematicState.Success -> {
                    if (loadedItems.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = CinemaMuted,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Ничего не можем найти по данному запросу",
                                color = CinemaTextGray,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        val gridState = rememberSavedLazyGridState("thematic_${effectiveUrl}", viewModel)
                        val shouldLoadMore by remember {
                            derivedStateOf {
                                val totalItems = gridState.layoutInfo.totalItemsCount
                                val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                totalItems >= 12 && lastVisibleIndex >= totalItems - 4 && gridState.canScrollForward
                            }
                        }

                        LaunchedEffect(shouldLoadMore, isLoadingMore, isEndReached) {
                            if (shouldLoadMore && !isLoadingMore && !isEndReached) {
                                loadNextPage()
                            }
                        }

                        val configuration = LocalConfiguration.current
                        val isLandscape = isTvMode || configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        val cardGridMode by viewModel.cardGridMode.collectAsState()

                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val resolvedGrid = remember(cardGridMode, maxWidth, maxHeight, isLandscape) {
                                com.example.ui.tv.CardGridEngine.calculate(
                                    cardGridMode = cardGridMode,
                                    availableWidth = maxWidth - 32.dp,
                                    availableHeight = maxHeight - 16.dp,
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
                                    .testTag("thematic_items_grid")
                            ) {
                                itemsIndexed(
                                    items = loadedItems,
                                    key = { _, item -> item.id }
                                ) { index, item ->
                                    val navigateToItem: (Int) -> Unit = { targetIndex ->
                                        val total = loadedItems.size
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
                                        totalItems = loadedItems.size,
                                        columnsCount = columnsCount,
                                        cardHeight = cardHeight,
                                        onNavigateIndex = navigateToItem,
                                        focusRequester = if (isTvMode) getFocusRequesterForIndex(index) else null,
                                        onUp = {
                                            if (isCollection) {
                                                sectionFocusRequester.requestFocusSafe()
                                            } else {
                                                backFocusRequester.requestFocusSafe()
                                            }
                                        },
                                        onLeft = { backFocusRequester.requestFocusSafe() },
                                        onClick = { onNavigateToDetail(item) }
                                    )
                                }

                                if (isLoadingMore && loadedItems.size >= 8) {
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
