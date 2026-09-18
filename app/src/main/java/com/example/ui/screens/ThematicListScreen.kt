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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.RezkaItem
import com.example.data.RezkaService
import com.example.ui.theme.*
import com.example.ui.tv.dpadScrollable
import com.example.ui.tv.requestFocusSafe
import com.example.ui.tv.tvFocusableItem
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
    isTvMode: Boolean = false
) {
    val coroutineScope = rememberCoroutineScope()
    var state by remember(url) { mutableStateOf<ThematicState>(ThematicState.Loading) }
    var currentPage by remember(url) { mutableStateOf(1) }
    var isLoadingMore by remember(url) { mutableStateOf(false) }
    var isEndReached by remember(url) { mutableStateOf(false) }
    val loadedItems = remember(url) { mutableStateListOf<RezkaItem>() }

    // First load
    LaunchedEffect(url) {
        state = ThematicState.Loading
        currentPage = 1
        isEndReached = false
        isLoadingMore = false
        loadedItems.clear()
        try {
            val items = RezkaService.getCustomCatalog(url, 1)
            loadedItems.addAll(items)
            state = ThematicState.Success(loadedItems)
        } catch (e: Exception) {
            state = ThematicState.Error(e.message ?: "Ошибка загрузки")
        }
    }

    // Load next page
    fun loadNextPage() {
        if (isLoadingMore || isEndReached || state !is ThematicState.Success) return
        isLoadingMore = true
        coroutineScope.launch {
            try {
                val nextPage = currentPage + 1
                val items = RezkaService.getCustomCatalog(url, nextPage)
                val newUniqueItems = items.filterNot { newItem -> loadedItems.any { it.id == newItem.id } }
                if (newUniqueItems.isEmpty()) {
                    isEndReached = true
                } else {
                    loadedItems.addAll(newUniqueItems)
                    currentPage = nextPage
                }
            } catch (_: Exception) {
                // Keep existing items intact
            } finally {
                isLoadingMore = false
            }
        }
    }

    BackHandler(onBack = onBack)

    val backFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (isTvMode) {
            backFocusRequester.requestFocusSafe()
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
                    IconButton(onClick = onBack) {
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = CinemaPrimary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = currentState.message,
                            color = CinemaTextWhite,
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    state = ThematicState.Loading
                                    try {
                                        val items = RezkaService.getCustomCatalog(url, 1)
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
                                text = "Ничего не найдено",
                                color = CinemaTextGray,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        val gridState = rememberLazyGridState()
                        val shouldLoadMore by remember {
                            derivedStateOf {
                                val totalItems = gridState.layoutInfo.totalItemsCount
                                val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                totalItems > 0 && lastVisibleIndex >= totalItems - 4
                            }
                        }

                        LaunchedEffect(shouldLoadMore, isLoadingMore, isEndReached) {
                            if (shouldLoadMore && !isLoadingMore && !isEndReached) {
                                loadNextPage()
                            }
                        }

                        LazyVerticalGrid(
                            columns = if (isTvMode) GridCells.Adaptive(minSize = 135.dp) else GridCells.Fixed(2),
                            state = gridState,
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .dpadScrollable(gridState)
                                .testTag("thematic_items_grid")
                        ) {
                            items(
                                items = loadedItems,
                                key = { it.id }
                            ) { item ->
                                RezkaItemCard(
                                    item = item,
                                    onClick = { onNavigateToDetail(item) }
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
