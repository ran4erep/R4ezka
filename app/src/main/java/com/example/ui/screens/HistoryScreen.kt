package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.example.ui.util.rememberSavedLazyListState
import com.example.ui.util.rememberSavedLazyGridState
import com.example.ui.util.AdaptivePosterBadge
import com.example.ui.util.SeriesBadgeMode
import com.example.ui.haptics.bounceOverscroll
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.roundToInt
import com.example.data.AggregatedHistoryItem
import com.example.data.RezkaItem
import com.example.data.RezkaService
import com.example.data.RezkaType
import com.example.ui.RezkaViewModel
import com.example.ui.effects.subtleCardBounce
import com.example.ui.theme.*
import com.example.ui.tv.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HistoryScreen(
    viewModel: RezkaViewModel,
    onNavigateToDetail: (RezkaItem) -> Unit,
    onNavigateLeftToSidebar: (() -> Unit)? = null,
    firstItemFocusRequester: FocusRequester? = null,
    isTopScreen: Boolean = true,
    onRegisterFocusRestorer: (((() -> Unit)) -> Unit)? = null,
    isTvMode: Boolean = false,
    tvResolvedGrid: com.example.ui.tv.ResolvedCardGrid? = null,
    modifier: Modifier = Modifier
) {
    val historyList by viewModel.aggregatedWatchHistory.collectAsStateWithLifecycle()
    val isCheckingUpdates by viewModel.isCheckingSeriesUpdates.collectAsStateWithLifecycle()
    val loadingMovieId by viewModel.loadingMovieId.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberSavedLazyListState("history", viewModel)
    val emptyFocusRequester = firstItemFocusRequester ?: remember { FocusRequester() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.checkHistorySeriesUpdates(force = true)
    }

    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun getFocusRequesterForIndex(idx: Int): FocusRequester {
        return if (idx == 0 && firstItemFocusRequester != null) {
            firstItemFocusRequester
        } else {
            itemFocusRequesters.getOrPut(idx) { FocusRequester() }
        }
    }

    val cardGridMode by viewModel.cardGridMode.collectAsState()
    val gridState = rememberSavedLazyGridState("history_grid", viewModel)

    val restoreFocus: () -> Unit = {
        coroutineScope.launch {
            if (historyList.isNotEmpty()) {
                val savedItemId = viewModel.getTvHistoryFocusedItemId()
                val savedIndex = viewModel.getTvHistoryFocusedIndex()
                val targetIndex = when {
                    savedItemId != null -> {
                        val idx = historyList.indexOfFirst { it.itemId == savedItemId }
                        if (idx >= 0) idx else (savedIndex?.coerceIn(0, historyList.lastIndex) ?: 0)
                    }
                    savedIndex != null -> savedIndex.coerceIn(0, historyList.lastIndex)
                    else -> 0
                }
                try {
                    if (isTvMode) {
                        gridState.scrollToItem(targetIndex)
                    } else {
                        listState.scrollToItem(targetIndex)
                    }
                } catch (_: Throwable) {}
                for (attempt in 0..16) {
                    kotlinx.coroutines.delay(if (attempt == 0) 25L else 35L)
                    try {
                        getFocusRequesterForIndex(targetIndex).requestFocus()
                        break
                    } catch (_: Throwable) {}
                }
            } else {
                for (attempt in 0..10) {
                    kotlinx.coroutines.delay(if (attempt == 0) 25L else 35L)
                    try {
                        emptyFocusRequester.requestFocus()
                        break
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    LaunchedEffect(restoreFocus) {
        onRegisterFocusRestorer?.invoke(restoreFocus)
    }

    var hasSetFocusOnTop by remember { mutableStateOf(false) }
    LaunchedEffect(isTopScreen, historyList.size) {
        if (!isTopScreen) {
            hasSetFocusOnTop = false
            return@LaunchedEffect
        }
        if (hasSetFocusOnTop) return@LaunchedEffect
        hasSetFocusOnTop = true
        restoreFocus()
        // Повторный вызов через 450мс гарантирует восстановление после схлопывания экрана фильма в AnimatedContent (420мс)
        kotlinx.coroutines.delay(450L)
        restoreFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        // ---- HEADER ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ИСТОРИЯ",
                        color = CinemaPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp
                    )
                    if (isCheckingUpdates) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            color = CinemaPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Text(
                    text = if (isCheckingUpdates) "Синхронизация серий..." else "Прогресс ваших просмотров",
                    color = if (isCheckingUpdates) CinemaPrimary.copy(alpha = 0.85f) else CinemaTextGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (historyList.isNotEmpty()) {
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .size(40.dp)
                        .tvFocusableItem(
                            onClick = { viewModel.clearAllHistory() },
                            scaleFactor = 1.1f,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .testTag("clear_all_history_button")
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Очистить историю",
                            tint = CinemaPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (isTvMode) Modifier.padding(horizontal = 16.dp) else Modifier)
        ) {
            if (historyList.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                if (onNavigateLeftToSidebar != null) {
                                    onNavigateLeftToSidebar()
                                    true
                                } else false
                            } else false
                        }
                        .tvFocusableItem(
                            onClick = {},
                            shape = RoundedCornerShape(12.dp),
                            focusRequester = emptyFocusRequester,
                            hideBorder = true
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = CinemaMuted,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "История пуста",
                        color = CinemaTextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Продолжайте просмотр там, где остановились",
                        color = CinemaTextGray,
                        fontSize = 12.sp
                    )
                }
            } else if (isTvMode) {
                // ---- РЕЖИМ ДЛЯ ТЕЛЕВИЗОРОВ: специализированная ТВ-сетка, согласованная с каталогом ----
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val resolvedGrid = if (isTvMode && tvResolvedGrid != null) {
                        tvResolvedGrid
                    } else {
                        remember(cardGridMode, maxWidth, maxHeight) {
                            com.example.ui.tv.CardGridEngine.calculateForTv(
                                cardGridMode = cardGridMode,
                                availableWidth = maxWidth,
                                availableHeight = maxHeight
                            )
                        }
                    }

                    val tvGridCols = resolvedGrid.columns
                    val isDense = tvGridCols >= 5
                    val buttonHeight = if (tvGridCols >= 7) 24.dp else if (isDense) 28.dp else 32.dp
                    val spacerHeight = if (isDense) 4.dp else 6.dp
                    val itemCardHeight = resolvedGrid.cardHeight

                    val markWatchedFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
                    val deleteItemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(tvGridCols),
                        state = gridState,
                        contentPadding = PaddingValues(top = 10.dp, bottom = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(resolvedGrid.horizontalSpacing),
                        verticalArrangement = Arrangement.spacedBy(resolvedGrid.verticalSpacing),
                        modifier = Modifier
                            .fillMaxSize()
                            .bounceOverscroll(androidx.compose.foundation.gestures.Orientation.Vertical)
                            .dpadScrollable(gridState)
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                    if (onNavigateLeftToSidebar != null) {
                                        onNavigateLeftToSidebar()
                                        true
                                    } else false
                                } else false
                            }
                            .testTag("history_grid")
                    ) {
                        itemsIndexed(
                            items = historyList,
                            key = { _, it -> it.itemId }
                        ) { index, history ->
                            val navigateToItem: (Int) -> Unit = { targetIndex ->
                                val total = historyList.size
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

                            val cardRequester = getFocusRequesterForIndex(index)
                            val markWatchedRequester = markWatchedFocusRequesters.getOrPut(index) { FocusRequester() }
                            val deleteRequester = deleteItemFocusRequesters.getOrPut(index) { FocusRequester() }
                            val isFullyWatched = history.isFullyWatched
                            val isFirstCol = index % tvGridCols == 0

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp)
                            ) {
                                TvHistoryCard(
                                    history = history,
                                    index = index,
                                    totalItems = historyList.size,
                                    columnCount = tvGridCols,
                                    cardHeight = itemCardHeight,
                                    cardWidth = resolvedGrid.estimatedCardWidth,
                                    isBouncing = history.itemId == loadingMovieId,
                                    onClick = {
                                        viewModel.setTvHistoryFocusedItem(history.itemId, index)
                                        val itemType = if (history.isSeries) RezkaType.SERIES else RezkaType.MOVIE
                                        val targetUrl = RezkaService.adjustUrlToCurrentMirror(history.url, itemType, history.itemId)
                                        val item = RezkaItem(
                                            id = history.itemId,
                                            title = history.title,
                                            subtitle = "",
                                            imageUrl = history.imageUrl,
                                            rating = "",
                                            url = targetUrl,
                                            type = itemType
                                        )
                                        onNavigateToDetail(item)
                                    },
                                    onNavigateIndex = navigateToItem,
                                    onUp = {
                                        if (index >= tvGridCols) {
                                            deleteItemFocusRequesters.getOrPut(index - tvGridCols) { FocusRequester() }.requestFocusSafe()
                                        }
                                    },
                                    onLeft = {
                                        if (isFirstCol) {
                                            onNavigateLeftToSidebar?.invoke()
                                        } else {
                                            navigateToItem(index - 1)
                                        }
                                    },
                                    onDown = {
                                        if (isFullyWatched) {
                                            deleteRequester.requestFocusSafe()
                                        } else {
                                            markWatchedRequester.requestFocusSafe()
                                        }
                                    },
                                    focusRequester = cardRequester,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(spacerHeight))

                                val btnShape = RoundedCornerShape(if (isDense) 6.dp else 8.dp)
                                if (isFullyWatched) {
                                    val haptic = HapticEngine.get()
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(buttonHeight)
                                            .clip(btnShape)
                                            .background(CinemaDark)
                                            .border(1.dp, CinemaPrimary.copy(alpha = 0.8f), btnShape)
                                            .focusRequester(deleteRequester)
                                            .onKeyEvent { keyEvent ->
                                                if (keyEvent.type == KeyEventType.KeyDown) {
                                                    when (keyEvent.nativeKeyEvent.keyCode) {
                                                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                            if (isFirstCol) {
                                                                if (onNavigateLeftToSidebar != null) {
                                                                    onNavigateLeftToSidebar()
                                                                    true
                                                                } else false
                                                            } else {
                                                                deleteItemFocusRequesters.getOrPut(index - 1) { FocusRequester() }.requestFocusSafe()
                                                                true
                                                            }
                                                        }
                                                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                            if ((index + 1) % tvGridCols != 0 && index < historyList.size - 1) {
                                                                deleteItemFocusRequesters.getOrPut(index + 1) { FocusRequester() }.requestFocusSafe()
                                                                true
                                                            } else true
                                                        }
                                                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                                            cardRequester.requestFocusSafe()
                                                            true
                                                        }
                                                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                            val nextIndex = index + tvGridCols
                                                            if (nextIndex < historyList.size) {
                                                                getFocusRequesterForIndex(nextIndex).requestFocusSafe()
                                                                true
                                                            } else true
                                                        }
                                                        else -> false
                                                    }
                                                } else false
                                            }
                                            .tvFocusableItem(
                                                onClick = {
                                                    haptic.perform(HapticType.WARNING)
                                                    viewModel.deleteHistoryByItemId(history.itemId)
                                                },
                                                scaleFactor = 1.0f,
                                                focusedBorderColor = CinemaPrimary,
                                                shape = btnShape,
                                                focusRequester = deleteRequester
                                            )
                                            .testTag("delete_history_${history.itemId}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Удалить из истории",
                                                tint = CinemaPrimary,
                                                modifier = Modifier.size(if (tvGridCols >= 7) 12.dp else if (isDense) 14.dp else 16.dp)
                                            )
                                            if (tvGridCols < 8) {
                                                Spacer(modifier = Modifier.width(if (isDense) 3.dp else 5.dp))
                                                Text(
                                                    text = "Удалить",
                                                    color = CinemaTextWhite,
                                                    fontSize = if (tvGridCols >= 7) 8.5.sp else if (isDense) 9.5.sp else 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(buttonHeight),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(btnShape)
                                                .background(CinemaDark)
                                                .border(1.dp, Color.White.copy(alpha = 0.12f), btnShape)
                                                .focusRequester(markWatchedRequester)
                                                .onKeyEvent { keyEvent ->
                                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                                        when (keyEvent.nativeKeyEvent.keyCode) {
                                                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                                if (isFirstCol) {
                                                                    onNavigateLeftToSidebar?.invoke()
                                                                    true
                                                                } else {
                                                                    deleteItemFocusRequesters.getOrPut(index - 1) { FocusRequester() }.requestFocusSafe()
                                                                    true
                                                                }
                                                            }
                                                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                                deleteRequester.requestFocusSafe()
                                                                true
                                                            }
                                                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                                                cardRequester.requestFocusSafe()
                                                                true
                                                            }
                                                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                                val nextIndex = index + tvGridCols
                                                                if (nextIndex < historyList.size) {
                                                                    getFocusRequesterForIndex(nextIndex).requestFocusSafe()
                                                                    true
                                                                } else true
                                                            }
                                                            else -> false
                                                        }
                                                    } else false
                                                }
                                                .tvFocusableItem(
                                                    onClick = {
                                                        viewModel.toggleHistoryWatched(history.itemId)
                                                    },
                                                    scaleFactor = 1.0f,
                                                    focusedBorderColor = CinemaPrimary,
                                                    shape = btnShape,
                                                    focusRequester = markWatchedRequester
                                                )
                                                .testTag("mark_watched_history_${history.itemId}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Отметить просмотренным",
                                                    tint = CinemaMuted,
                                                    modifier = Modifier.size(if (isDense) 13.dp else 15.dp)
                                                )
                                            if (tvGridCols < 8) {
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "Просмотрено",
                                                    color = CinemaTextGray,
                                                    fontSize = if (tvGridCols >= 7) 8.sp else if (isDense) 9.sp else 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                            }
                                        }

                                        val haptic = HapticEngine.get()
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(btnShape)
                                                .background(CinemaDark)
                                                .border(1.dp, Color.White.copy(alpha = 0.12f), btnShape)
                                                .focusRequester(deleteRequester)
                                                .onKeyEvent { keyEvent ->
                                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                                        when (keyEvent.nativeKeyEvent.keyCode) {
                                                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                                markWatchedRequester.requestFocusSafe()
                                                                true
                                                            }
                                                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                                if ((index + 1) % tvGridCols != 0 && index < historyList.size - 1) {
                                                                    markWatchedFocusRequesters.getOrPut(index + 1) { FocusRequester() }.requestFocusSafe()
                                                                    true
                                                                } else true
                                                            }
                                                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                                                cardRequester.requestFocusSafe()
                                                                true
                                                            }
                                                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                                val nextIndex = index + tvGridCols
                                                                if (nextIndex < historyList.size) {
                                                                    getFocusRequesterForIndex(nextIndex).requestFocusSafe()
                                                                    true
                                                                } else true
                                                            }
                                                            else -> false
                                                        }
                                                    } else false
                                                }
                                                .tvFocusableItem(
                                                    onClick = {
                                                        haptic.perform(HapticType.WARNING)
                                                        viewModel.deleteHistoryByItemId(history.itemId)
                                                    },
                                                    scaleFactor = 1.0f,
                                                    focusedBorderColor = CinemaPrimary,
                                                    shape = btnShape,
                                                    focusRequester = deleteRequester
                                                )
                                                .testTag("delete_history_${history.itemId}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Удалить",
                                                    tint = CinemaMuted,
                                                    modifier = Modifier.size(if (isDense) 13.dp else 15.dp)
                                                )
                                            if (tvGridCols < 8) {
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "Удалить",
                                                    color = CinemaTextGray,
                                                    fontSize = if (tvGridCols >= 7) 8.sp else if (isDense) 9.sp else 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false
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
            } else {
                // ---- РЕЖИМ ДЛЯ СМАРТФОНОВ: привычный вертикальный мобильный список ----
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .bounceOverscroll(androidx.compose.foundation.gestures.Orientation.Vertical)
                        .dpadScrollable(listState)
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                if (onNavigateLeftToSidebar != null) {
                                    onNavigateLeftToSidebar()
                                    true
                                } else false
                            } else false
                        }
                        .testTag("history_list")
                ) {
                    itemsIndexed(
                        items = historyList,
                        key = { _, it -> it.itemId }
                    ) { index, history ->
                        HistoryCardItem(
                            history = history,
                            isBouncing = history.itemId == loadingMovieId,
                            isFirst = index == 0,
                            focusRequester = getFocusRequesterForIndex(index),
                            onLeft = onNavigateLeftToSidebar,
                            onClick = {
                                viewModel.setTvHistoryFocusedItem(history.itemId, index)
                                val itemType = if (history.isSeries) RezkaType.SERIES else RezkaType.MOVIE
                                val targetUrl = RezkaService.adjustUrlToCurrentMirror(history.url, itemType, history.itemId)
                                val item = RezkaItem(
                                    id = history.itemId,
                                    title = history.title,
                                    subtitle = "",
                                    imageUrl = history.imageUrl,
                                    rating = "",
                                    url = targetUrl,
                                    type = itemType
                                )
                                onNavigateToDetail(item)
                            },
                            onMarkWatched = { viewModel.toggleHistoryWatched(history.itemId) },
                            onDelete = { viewModel.deleteHistoryByItemId(history.itemId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryCardItem(
    history: AggregatedHistoryItem,
    onClick: () -> Unit,
    onMarkWatched: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isBouncing: Boolean = false,
    isFirst: Boolean = false,
    focusRequester: FocusRequester? = null,
    onLeft: (() -> Unit)? = null
) {
    val isFullyWatched = history.isFullyWatched
    val progressFraction = remember(history.totalProgressFraction, isFullyWatched) {
        if (isFullyWatched) 1f else history.totalProgressFraction.coerceIn(0f, 0.99f)
    }
    val progressPercentage = remember(progressFraction, isFullyWatched) {
        if (isFullyWatched) 100 else (progressFraction * 100f).toInt().coerceIn(0, 99)
    }

    val cardRequester = focusRequester ?: remember { FocusRequester() }
    val markWatchedRequester = remember { FocusRequester() }
    val deleteRequester = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Главная карточка элемента — занимает всю ширину экрана
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .subtleCardBounce(
                    isBouncing = isBouncing,
                    shape = RoundedCornerShape(12.dp)
                )
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (onLeft != null) {
                                    onLeft()
                                    true
                                } else false
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                if (isFirst) {
                                    // Не улетаем на сайдбар при нажатии вверх на первом элементе
                                    true
                                } else false
                            }
                            else -> false
                        }
                    } else false
                }
                .tvFocusableItem(
                    onClick = onClick, 
                    scaleFactor = 1.015f, 
                    shape = RoundedCornerShape(12.dp),
                    focusRequester = cardRequester,
                    hideBorder = isBouncing
                )
                .testTag("history_item_${history.itemId}"),
            colors = CardDefaults.cardColors(containerColor = CinemaDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Poster thumbnail
                Box(
                    modifier = Modifier
                        .size(width = 65.dp, height = 95.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    AsyncImage(
                        model = history.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay Play Icon
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Смотреть",
                            tint = CinemaTextWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Info texts
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically)
                ) {
                    Text(
                        text = history.title,
                        color = CinemaTextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (history.isSeries) {
                        val currentEpIdx = if (history.currentEpisodeIndex > 0) history.currentEpisodeIndex else RezkaViewModel.parseEpisodeNumber(history.latestEpisode)
                        Text(
                            text = "Сезон ${history.latestSeason}, Серия ${history.latestEpisode}",
                            color = CinemaPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        val progressText = if (history.isFullyWatched || (history.watchedEpisodesCount >= history.totalEpisodesCount && history.totalEpisodesCount > 0)) {
                            "Все ${history.totalEpisodesCount} серий"
                        } else if (history.totalEpisodesCount > 0 && currentEpIdx > 0) {
                            "Серия $currentEpIdx из ${history.totalEpisodesCount}"
                        } else {
                            "Серия ${history.latestEpisode}"
                        }
                        Text(
                            text = progressText,
                            color = CinemaTextGray,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else {
                        Text(
                            text = "Полный фильм",
                            color = CinemaTextGray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (history.latestTranslatorName.isNotEmpty()) {
                            Text(
                                text = history.latestTranslatorName,
                                color = CinemaTextGray,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Progress Meter Bar
                    LinearProgressIndicator(
                        progress = { if (isFullyWatched) 1.0f else progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = if (isFullyWatched) CinemaGreen else CinemaPrimary,
                        trackColor = CinemaSecondary,
                        drawStopIndicator = {}
                    )

                    Text(
                        text = if (history.isSeries) {
                            if (isFullyWatched) "100% — Все серии" else "$progressPercentage% общего прогресса"
                        } else {
                            if (isFullyWatched) "100%" else "$progressPercentage%"
                        },
                        color = if (isFullyWatched) CinemaGreen else CinemaTextGray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (isFullyWatched) {
            // Кнопка на всю ширину: "Удалить из истории"
            Surface(
                color = CinemaDark,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    if (onLeft != null) {
                                        onLeft()
                                        true
                                    } else false
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                    cardRequester.requestFocusSafe()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
                    .tvFocusableItem(
                        onClick = onDelete,
                        scaleFactor = 1.01f,
                        focusedBorderColor = CinemaPrimary,
                        shape = RoundedCornerShape(8.dp),
                        focusRequester = deleteRequester
                    )
                    .testTag("history_item_delete_${history.itemId}"),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Удалить из истории",
                        tint = CinemaMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Удалить из истории",
                        color = CinemaTextGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            // Ряд двух кнопок под карточкой: слева "Просмотрено", справа "Удалить"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка слева: "Просмотрено"
                Surface(
                    color = CinemaDark,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        if (onLeft != null) {
                                            onLeft()
                                            true
                                        } else false
                                    }
                                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        deleteRequester.requestFocusSafe()
                                        true
                                    }
                                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                        cardRequester.requestFocusSafe()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                        .tvFocusableItem(
                            onClick = onMarkWatched,
                            scaleFactor = 1.01f,
                            focusedBorderColor = CinemaPrimary,
                            shape = RoundedCornerShape(8.dp),
                            focusRequester = markWatchedRequester
                        )
                        .testTag("history_item_mark_watched_${history.itemId}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Отметить просмотренным",
                            tint = CinemaMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Просмотрено",
                            color = CinemaTextGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Кнопка справа: "Удалить"
                Surface(
                    color = CinemaDark,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        markWatchedRequester.requestFocusSafe()
                                        true
                                    }
                                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                        cardRequester.requestFocusSafe()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                        .tvFocusableItem(
                            onClick = onDelete,
                            scaleFactor = 1.01f,
                            focusedBorderColor = CinemaPrimary,
                            shape = RoundedCornerShape(8.dp),
                            focusRequester = deleteRequester
                        )
                        .testTag("history_item_delete_${history.itemId}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Удалить из истории",
                            tint = CinemaMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Удалить",
                            color = CinemaTextGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Высокопроизводительная специализированная карточка элемента истории для 10-foot TV интерфейса.
 * Согласована по геометрии, шрифтовым шкалам и логике сетки с карточками каталога и избранного.
 */
@Composable
fun TvHistoryCard(
    history: AggregatedHistoryItem,
    index: Int,
    totalItems: Int,
    columnCount: Int,
    cardHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    cardWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    isBouncing: Boolean = false,
    onClick: () -> Unit,
    onNavigateIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null
) {
    val safeCols = if (columnCount > 0) columnCount else 4
    val isFirstColumn = index % safeCols == 0

    val isDense = columnCount >= 6 || (cardHeight != androidx.compose.ui.unit.Dp.Unspecified && cardHeight.value < 140f)
    val isUltraDense = columnCount >= 8 || (cardHeight != androidx.compose.ui.unit.Dp.Unspecified && cardHeight.value < 100f)

    var isFocused by remember { mutableStateOf(false) }
    val isFullyWatched = history.isFullyWatched
    val progressFraction = remember(history.totalProgressFraction, isFullyWatched) {
        if (isFullyWatched) 1.0f else history.totalProgressFraction.coerceIn(0f, 1f)
    }
    val progressPercentage = remember(progressFraction, isFullyWatched) {
        if (isFullyWatched) 100 else (progressFraction * 100f).toInt().coerceIn(0, 99)
    }

    val playCircleSize = remember(columnCount) {
        if (columnCount >= 8) 22.dp else if (columnCount >= 6) 28.dp else if (columnCount >= 5) 34.dp else 40.dp
    }
    val playIconSize = remember(columnCount) {
        if (columnCount >= 8) 13.dp else if (columnCount >= 6) 17.dp else if (columnCount >= 5) 20.dp else 24.dp
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (cardHeight != androidx.compose.ui.unit.Dp.Unspecified) Modifier.height(cardHeight) else Modifier)
            .subtleCardBounce(
                isBouncing = isBouncing,
                shape = RoundedCornerShape(if (isUltraDense) 6.dp else if (isDense) 8.dp else 12.dp)
            )
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (onDown != null) {
                                onDown()
                                true
                            } else {
                                val targetIndex = index + safeCols
                                if (targetIndex < totalItems) {
                                    onNavigateIndex(targetIndex)
                                    true
                                } else if (index < totalItems - 1) {
                                    onNavigateIndex(totalItems - 1)
                                    true
                                } else true
                            }
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (index >= safeCols) {
                                onNavigateIndex(index - safeCols)
                                true
                            } else if (onUp != null) {
                                onUp()
                                true
                            } else false
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
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
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
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
                onFocused = { isFocused = true },
                onFocusChanged = { isFocused = it },
                scaleFactor = 1.0f,
                focusedBorderWidth = if (isUltraDense) 1.5.dp else 2.dp,
                shape = RoundedCornerShape(if (isUltraDense) 6.dp else if (isDense) 8.dp else 12.dp),
                focusRequester = focusRequester,
                hideBorder = isBouncing
            )
            .testTag("tv_history_card_${history.itemId}"),
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
                            Modifier.aspectRatio(0.68f)
                        }
                    )
            ) {
                AsyncImage(
                    model = history.imageUrl,
                    contentDescription = history.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Полупрозрачный оверлей с круглой иконкой Play по центру постера (как на телефоне)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (isFocused) 0.15f else 0.28f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isFocused) CinemaPrimary else Color.Black.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (isFocused) CinemaPrimary else Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier.size(playCircleSize)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Смотреть",
                                tint = if (isFocused) CinemaBlack else CinemaTextWhite,
                                modifier = Modifier.size(playIconSize)
                            )
                        }
                    }
                }
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
                    text = history.title,
                    color = CinemaTextWhite,
                    fontSize = if (isUltraDense) 8.5.sp else if (isDense) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isUltraDense || (cardHeight != androidx.compose.ui.unit.Dp.Unspecified && cardHeight.value >= 85f)) {
                    Spacer(modifier = Modifier.height(1.dp))
                    val subtitleText = remember(history) {
                        if (history.isSeries) {
                            if (history.isFullyWatched) "Все серии • 100%"
                            else "С${history.latestSeason} Э${history.latestEpisode} • $progressPercentage%"
                        } else {
                            if (history.isFullyWatched) "Просмотрено • 100%"
                            else if (progressPercentage > 0) "Просмотрено $progressPercentage%"
                            else "Фильм"
                        }
                    }
                    Text(
                        text = subtitleText,
                        color = if (history.isFullyWatched) CinemaGreen else CinemaTextGray,
                        fontSize = if (isUltraDense) 6.5.sp else if (isDense) 8.sp else 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(if (isUltraDense) 2.dp else 4.dp))

                // Полоса прогресса просмотра, как на телефоне (отдельная полоска под текстом, не встроена в постер!)
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isUltraDense) 2.5.dp else 3.5.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (isFullyWatched) CinemaGreen else CinemaPrimary,
                    trackColor = CinemaSecondary,
                    drawStopIndicator = {}
                )
            }
        }
    }
}

