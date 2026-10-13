package com.example.ui.util

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.example.ui.RezkaViewModel

/**
 * Высокопроизводительный движок сохранения и адаптивной математической конвертации
 * скролла сетки (LazyVerticalGrid) между вертикальной (Portrait) и горизонтальной (Landscape)
 * ориентациями экрана с минимальной нагрузкой на процессор (O(1) операций).
 */
@Composable
fun rememberSavedLazyGridState(
    key: String,
    viewModel: RezkaViewModel,
    initialColumns: Int = 1
): LazyGridState {
    val initialPos = remember(key) { viewModel.getScrollPosition(key, targetColumns = initialColumns) }
    val state = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialPos.index,
        initialFirstVisibleItemScrollOffset = initialPos.offset
    )

    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val maxCol = state.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column }
                val currentCols = if (maxCol != null && maxCol >= 0) maxCol + 1 else 1
                viewModel.saveScrollPosition(key, index, offset, currentCols)
            }
    }

    return state
}

/**
 * Расширение для мгновенной адаптивной конвертации скролла при динамическом изменении
 * количества колонок сетки (например, переворот телефона из 2 колонок в 4-6 колонок и обратно).
 * Выравнивает сетку по строке якорного элемента без обрезания постеров и без скачков.
 */
@Composable
fun LazyGridState.SyncOrientationColumns(
    key: String,
    viewModel: RezkaViewModel,
    columns: Int
) {
    if (columns <= 0) return

    var lastColumns by remember(key) {
        val saved = viewModel.getScrollPosition(key)
        mutableIntStateOf(if (saved.columns > 0) saved.columns else columns)
    }

    LaunchedEffect(columns, key) {
        val oldCols = lastColumns
        val newCols = columns
        if (oldCols > 0 && newCols > 0 && oldCols != newCols) {
            lastColumns = newCols
            val currentIndex = firstVisibleItemIndex
            if (currentIndex > 0) {
                // Высокопроизводительная математическая конвертация положения:
                // oldRow = номер строки в старой сетке
                // anchorIndex = первый элемент этой строки
                // newRow = номер строки в новой сетке
                // targetIndex = начало строки в новой сетке
                val oldRow = currentIndex / oldCols
                val anchorIndex = oldRow * oldCols
                val newRow = anchorIndex / newCols
                val targetIndex = (newRow * newCols).coerceAtLeast(0)
                try {
                    scrollToItem(targetIndex, 0)
                    viewModel.saveScrollPosition(key, targetIndex, 0, newCols)
                } catch (_: Throwable) {}
            } else {
                viewModel.saveScrollPosition(key, 0, 0, newCols)
            }
        }
    }
}

@Composable
fun rememberSavedLazyListState(
    key: String,
    viewModel: RezkaViewModel
): LazyListState {
    val initialPos = remember(key) { viewModel.getScrollPosition(key, targetColumns = 1) }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPos.index,
        initialFirstVisibleItemScrollOffset = initialPos.offset
    )

    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.saveScrollPosition(key, index, offset, 1)
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
            .collect { offset ->
                viewModel.saveScrollPosition(key, 0, offset, 1)
            }
    }

    return state
}
