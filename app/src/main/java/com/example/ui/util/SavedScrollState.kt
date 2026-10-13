package com.example.ui.util

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.example.ui.RezkaViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

private data class GridScrollSnapshot(
    val index: Int,
    val offset: Int,
    val totalItems: Int,
    val isScrolling: Boolean
)

@Composable
fun rememberSavedLazyGridState(
    key: String,
    viewModel: RezkaViewModel,
    columnsCount: Int = 1
): LazyGridState {
    val effectiveCols = if (columnsCount > 0) columnsCount else 1
    val initialPos = remember(key, effectiveCols) {
        viewModel.getScrollPosition(key, targetColumns = effectiveCols)
    }
    val state = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialPos.index,
        initialFirstVisibleItemScrollOffset = initialPos.offset
    )

    // При динамической смене количества колонок в текущем Composable (например при повороте экрана):
    LaunchedEffect(key, effectiveCols) {
        val converted = viewModel.getScrollPosition(key, targetColumns = effectiveCols)
        if (converted.index > 0 && kotlin.math.abs(state.firstVisibleItemIndex - converted.index) >= effectiveCols) {
            try {
                state.scrollToItem(converted.index, 0)
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(state, key, effectiveCols) {
        snapshotFlow {
            val total = state.layoutInfo.totalItemsCount
            val index = state.firstVisibleItemIndex
            val offset = state.firstVisibleItemScrollOffset
            val isScrolling = state.isScrollInProgress
            GridScrollSnapshot(index, offset, total, isScrolling)
        }
        .distinctUntilChanged()
        .collect { snapshot ->
            // Защита от затирания: сохраняем ТОЛЬКО если в сетке реально есть элементы (totalItems > 0)
            if (snapshot.totalItems > 0) {
                // Если индекс/оффсет больше 0, либо пользователь активно скроллит (даже в начало)
                if (snapshot.index > 0 || snapshot.offset > 0 || snapshot.isScrolling) {
                    viewModel.saveScrollPosition(
                        key = key,
                        index = snapshot.index,
                        offset = snapshot.offset,
                        columnCount = effectiveCols,
                        totalItems = snapshot.totalItems
                    )
                }
            }
        }
    }

    return state
}

private data class ListScrollSnapshot(
    val index: Int,
    val offset: Int,
    val totalItems: Int,
    val isScrolling: Boolean
)

@Composable
fun rememberSavedLazyListState(
    key: String,
    viewModel: RezkaViewModel
): LazyListState {
    val initialPos = remember(key) {
        viewModel.getScrollPosition(key, targetColumns = 1)
    }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPos.index,
        initialFirstVisibleItemScrollOffset = initialPos.offset
    )

    LaunchedEffect(state, key) {
        snapshotFlow {
            val total = state.layoutInfo.totalItemsCount
            val index = state.firstVisibleItemIndex
            val offset = state.firstVisibleItemScrollOffset
            val isScrolling = state.isScrollInProgress
            ListScrollSnapshot(index, offset, total, isScrolling)
        }
        .distinctUntilChanged()
        .collect { snapshot ->
            if (snapshot.totalItems > 0) {
                if (snapshot.index > 0 || snapshot.offset > 0 || snapshot.isScrolling) {
                    viewModel.saveScrollPosition(
                        key = key,
                        index = snapshot.index,
                        offset = snapshot.offset,
                        columnCount = 1,
                        totalItems = snapshot.totalItems
                    )
                }
            }
        }
    }

    return state
}

@Composable
fun rememberSavedScrollState(
    key: String,
    viewModel: RezkaViewModel
): ScrollState {
    val initialPos = remember(key) { viewModel.getScrollPosition(key) }
    val state = rememberScrollState(initial = initialPos.offset)

    LaunchedEffect(state, key) {
        snapshotFlow { state.value }
            .distinctUntilChanged()
            .collect { offset ->
                if (offset > 0 || state.isScrollInProgress) {
                    viewModel.saveScrollPosition(key, 0, offset)
                }
            }
    }

    return state
}
