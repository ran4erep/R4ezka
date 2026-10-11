package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.example.ui.util.AdaptivePosterBadge
import com.example.ui.util.SeriesBadgeMode
import com.example.ui.effects.subtleCardBounce
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ui.tv.requestFocusSafe
import com.example.ui.haptics.bounceOverscroll
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.RezkaItem
import com.example.ui.util.rememberSavedLazyGridState
import com.example.data.RezkaService
import com.example.data.RezkaType
import com.example.ui.RezkaViewModel
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.theme.*
import com.example.ui.tv.tvFocusableItem

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FavoritesScreen(
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
    val favorites by viewModel.favorites.collectAsState()

    LaunchedEffect(favorites.map { it.id }) {
        viewModel.refreshFavoritesInfo()
    }
    val cardGridMode by viewModel.cardGridMode.collectAsState()
    val seriesBadgeMode by viewModel.seriesBadgeMode.collectAsState()
    val loadingMovieId by viewModel.loadingMovieId.collectAsState()
    val parsedCardGrid = remember(cardGridMode) { RezkaService.parseCardGrid(cardGridMode) }
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ИЗБРАННОЕ",
                    color = CinemaPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = if (favorites.isNotEmpty()) "Сохраненные фильмы и сериалы (${favorites.size})" else "Сохраненные фильмы и сериалы",
                    color = CinemaTextGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (isTvMode) Modifier.padding(horizontal = 16.dp) else Modifier)
        ) {
            if (favorites.isEmpty()) {
                val emptyFocusRequester = firstItemFocusRequester ?: remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    onRegisterFocusRestorer?.invoke { emptyFocusRequester.requestFocusSafe() }
                }
                LaunchedEffect(Unit) {
                    emptyFocusRequester.requestFocusSafe()
                }
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
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        tint = CinemaMuted,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "В избранном пусто",
                        color = CinemaTextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Добавляйте фильмы со страниц описания",
                        color = CinemaTextGray,
                        fontSize = 12.sp
                    )
                }
            } else {
                val gridState = rememberSavedLazyGridState("favorites", viewModel)
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val coroutineScope = rememberCoroutineScope()
                    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
                    val deleteItemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

                    fun getFocusRequesterForIndex(idx: Int): FocusRequester {
                        return if (idx == 0 && firstItemFocusRequester != null) {
                            firstItemFocusRequester
                        } else {
                            itemFocusRequesters.getOrPut(idx) { FocusRequester() }
                        }
                    }

                    val restoreFocus: () -> Unit = {
                        coroutineScope.launch {
                            if (favorites.isNotEmpty()) {
                                val savedItemId = viewModel.getTvFavoritesFocusedItemId()
                                val savedIndex = viewModel.getTvFavoritesFocusedIndex()
                                val targetIndex = when {
                                    savedItemId != null -> {
                                        val idx = favorites.indexOfFirst { it.id == savedItemId }
                                        if (idx >= 0) idx else (savedIndex?.coerceIn(0, favorites.lastIndex) ?: 0)
                                    }
                                    savedIndex != null -> savedIndex.coerceIn(0, favorites.lastIndex)
                                    else -> 0
                                }
                                try {
                                    gridState.scrollToItem(targetIndex)
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
                                        (firstItemFocusRequester ?: FocusRequester()).requestFocus()
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
                    LaunchedEffect(isTopScreen, favorites.size) {
                        if (!isTopScreen) {
                            hasSetFocusOnTop = false
                            return@LaunchedEffect
                        }
                        if (hasSetFocusOnTop) return@LaunchedEffect
                        hasSetFocusOnTop = true
                        restoreFocus()
                        kotlinx.coroutines.delay(450L)
                        restoreFocus()
                    }

                    val resolvedGrid = if (isTvMode && tvResolvedGrid != null) {
                        tvResolvedGrid
                    } else {
                        remember(cardGridMode, maxWidth, maxHeight, isLandscape, isTvMode) {
                            if (isTvMode) {
                                com.example.ui.tv.CardGridEngine.calculateForTv(
                                    cardGridMode = cardGridMode,
                                    availableWidth = maxWidth,
                                    availableHeight = maxHeight
                                )
                            } else {
                                com.example.ui.tv.CardGridEngine.calculate(
                                    cardGridMode = cardGridMode,
                                    availableWidth = maxWidth - 32.dp,
                                    availableHeight = maxHeight - 16.dp,
                                    isLandscapeOrTv = isLandscape
                                )
                            }
                        }
                    }

                    val gridColumns = resolvedGrid.columns
                    val isDense = gridColumns >= 5
                    val buttonHeight = if (gridColumns >= 7) 24.dp else if (isDense) 28.dp else 32.dp
                    val spacerHeight = if (isDense) 4.dp else 6.dp
                    val itemCardHeight = if (isTvMode) {
                        resolvedGrid.cardHeight
                    } else {
                        if (resolvedGrid.cardHeight != androidx.compose.ui.unit.Dp.Unspecified) {
                            (resolvedGrid.cardHeight - buttonHeight - spacerHeight).coerceAtLeast(60.dp)
                        } else androidx.compose.ui.unit.Dp.Unspecified
                    }

                    val navigateToGridItem: (Int, Boolean) -> Unit = { targetIndex, focusButton ->
                        val total = favorites.size
                        if (total > 0) {
                            val clampedIndex = targetIndex.coerceIn(0, total - 1)
                            viewModel.setTvFavoritesFocusedItem(favorites[clampedIndex].id, clampedIndex)
                            coroutineScope.launch {
                                try {
                                    val layoutInfo = gridState.layoutInfo
                                    val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == clampedIndex }
                                    val isFullyVisible = if (visibleItem != null) {
                                        val top = visibleItem.offset.y
                                        val bottom = top + visibleItem.size.height
                                        top >= layoutInfo.viewportStartOffset && bottom <= layoutInfo.viewportEndOffset
                                    } else false

                                    if (!isFullyVisible) {
                                        gridState.scrollToItem(clampedIndex)
                                    }
                                } catch (_: Throwable) {
                                    try {
                                        gridState.scrollToItem(clampedIndex)
                                    } catch (_: Throwable) {}
                                }

                                val requester = if (focusButton) {
                                    deleteItemFocusRequesters.getOrPut(clampedIndex) { FocusRequester() }
                                } else {
                                    getFocusRequesterForIndex(clampedIndex)
                                }

                                for (attempt in 0..10) {
                                    if (attempt > 0) {
                                        kotlinx.coroutines.delay(20L)
                                    }
                                    try {
                                        requester.requestFocus()
                                        break
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns),
                        state = gridState,
                        contentPadding = PaddingValues(
                            top = if (isTvMode) 10.dp else 12.dp,
                            start = if (isTvMode) 0.dp else 16.dp,
                            end = if (isTvMode) 0.dp else 16.dp,
                            bottom = if (isTvMode) 48.dp else if (isLandscape) 16.dp else 80.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(resolvedGrid.horizontalSpacing),
                        verticalArrangement = Arrangement.spacedBy(resolvedGrid.verticalSpacing),
                        modifier = Modifier
                            .fillMaxSize()
                            .bounceOverscroll(androidx.compose.foundation.gestures.Orientation.Vertical)
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                    if (onNavigateLeftToSidebar != null) {
                                        onNavigateLeftToSidebar()
                                        true
                                    } else false
                                } else false
                            }
                            .testTag("favorites_grid")
                    ) {
                        itemsIndexed(
                            items = favorites,
                            key = { _, fav -> fav.id }
                        ) { index, fav ->
                            val itemType = RezkaType.valueOf(fav.type)
                            val item = RezkaItem(
                                id = fav.id,
                                title = fav.title,
                                subtitle = fav.subtitle,
                                imageUrl = fav.imageUrl,
                                rating = fav.rating,
                                url = RezkaService.adjustUrlToCurrentMirror(fav.url, itemType, fav.id),
                                type = itemType
                            )

                            val cardRequester = getFocusRequesterForIndex(index)
                            val deleteRequester = deleteItemFocusRequesters.getOrPut(index) { FocusRequester() }
                            val isFirstCol = index % gridColumns == 0

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp)
                            ) {
                                if (isTvMode) {
                                    TvFavoriteCard(
                                        item = item,
                                        index = index,
                                        totalItems = favorites.size,
                                        columnCount = gridColumns,
                                        cardHeight = itemCardHeight,
                                        cardWidth = resolvedGrid.estimatedCardWidth,
                                        seriesBadgeMode = seriesBadgeMode,
                                        isBouncing = item.id == loadingMovieId,
                                        onNavigateIndex = { targetIdx -> navigateToGridItem(targetIdx, false) },
                                        onDown = {
                                            deleteRequester.requestFocusSafe()
                                        },
                                        onUp = {
                                            if (index >= gridColumns) {
                                                navigateToGridItem(index - gridColumns, true)
                                            } else {
                                                coroutineScope.launch {
                                                    try { gridState.scrollToItem(0) } catch (_: Throwable) {}
                                                }
                                            }
                                        },
                                        onLeft = {
                                            if (isFirstCol) {
                                                onNavigateLeftToSidebar?.invoke()
                                            } else {
                                                navigateToGridItem(index - 1, false)
                                            }
                                        },
                                        focusRequester = cardRequester,
                                        onClick = {
                                            viewModel.setTvFavoritesFocusedItem(fav.id, index)
                                            onNavigateToDetail(item)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    RezkaItemCard(
                                        item = item,
                                        index = index,
                                        totalItems = favorites.size,
                                        columnsCount = gridColumns,
                                        cardHeight = itemCardHeight,
                                        cardWidth = resolvedGrid.estimatedCardWidth,
                                        seriesBadgeMode = seriesBadgeMode,
                                        isBouncing = item.id == loadingMovieId,
                                        onNavigateIndex = { targetIdx -> navigateToGridItem(targetIdx, false) },
                                        onDown = {
                                            deleteRequester.requestFocusSafe()
                                        },
                                        onUp = {
                                            if (index >= gridColumns) {
                                                navigateToGridItem(index - gridColumns, true)
                                            } else {
                                                coroutineScope.launch {
                                                    try { gridState.scrollToItem(0) } catch (_: Throwable) {}
                                                }
                                            }
                                        },
                                        onLeft = {
                                            if (isFirstCol) {
                                                onNavigateLeftToSidebar?.invoke()
                                            } else {
                                                navigateToGridItem(index - 1, false)
                                            }
                                        },
                                        focusRequester = cardRequester,
                                        onClick = {
                                            viewModel.setTvFavoritesFocusedItem(fav.id, index)
                                            onNavigateToDetail(item)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(modifier = Modifier.height(spacerHeight))

                                val haptic = HapticEngine.get()
                                val btnShape = RoundedCornerShape(if (isDense) 6.dp else 8.dp)
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
                                                        if ((index + 1) % gridColumns != 0 && index < favorites.size - 1) {
                                                            deleteItemFocusRequesters.getOrPut(index + 1) { FocusRequester() }.requestFocusSafe()
                                                            true
                                                        } else true
                                                    }
                                                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                                        cardRequester.requestFocusSafe()
                                                        true
                                                    }
                                                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                        val nextIndex = index + gridColumns
                                                        if (nextIndex < favorites.size) {
                                                            navigateToGridItem(nextIndex, false)
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
                                                viewModel.removeFavorite(fav.id)
                                            },
                                            scaleFactor = 1.0f,
                                            focusedBorderColor = CinemaPrimary,
                                            shape = btnShape,
                                            focusRequester = deleteRequester
                                        )
                                        .testTag("remove_favorite_${fav.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Удалить из избранного",
                                            tint = CinemaPrimary,
                                            modifier = Modifier.size(if (gridColumns >= 7) 12.dp else if (isDense) 14.dp else 16.dp)
                                        )
                                        if (gridColumns < 8) {
                                            Spacer(modifier = Modifier.width(if (isDense) 3.dp else 5.dp))
                                            Text(
                                                text = "Удалить",
                                                color = CinemaTextWhite,
                                                fontSize = if (gridColumns >= 7) 8.5.sp else if (isDense) 9.5.sp else 11.sp,
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
    }
}

/**
 * Специализированная ТВ-карточка для Избранного:
 * 1. Имеет точно такой же размер (высоту и пропорции), как и карточка в каталоге.
 * 2. Постер содержит стильный оверлей с круглой иконкой Play (как на телефоне).
 * 3. Поддерживает навигацию с пульта D-Pad и адаптивный бейдж серий.
 */
@Composable
private fun TvFavoriteCard(
    item: RezkaItem,
    index: Int,
    totalItems: Int,
    columnCount: Int,
    cardHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    cardWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    seriesBadgeMode: SeriesBadgeMode = RezkaService.seriesBadgeMode.value,
    isBouncing: Boolean = false,
    onClick: () -> Unit,
    onNavigateIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null
) {
    val safeCols = if (columnCount > 0) columnCount else 4
    val isFirstColumn = index % safeCols == 0

    val isDense = columnCount >= 6 || (cardHeight != androidx.compose.ui.unit.Dp.Unspecified && cardHeight.value < 140f)
    val isUltraDense = columnCount >= 8 || (cardHeight != androidx.compose.ui.unit.Dp.Unspecified && cardHeight.value < 100f)

    var isFocused by remember { mutableStateOf(false) }

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
                                if (onUp != null) onUp() else onNavigateIndex(index - safeCols)
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
            .testTag("tv_favorite_card_${item.id}"),
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
                if (!isUltraDense || (cardHeight != androidx.compose.ui.unit.Dp.Unspecified && cardHeight.value >= 85f)) {
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

