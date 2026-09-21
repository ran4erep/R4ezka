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

@Composable
fun rememberSavedLazyGridState(
    key: String,
    viewModel: RezkaViewModel
): LazyGridState {
    val initialPos = remember(key) { viewModel.getScrollPosition(key) }
    val state = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialPos.index,
        initialFirstVisibleItemScrollOffset = initialPos.offset
    )

    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.saveScrollPosition(key, index, offset)
            }
    }

    return state
}

@Composable
fun rememberSavedLazyListState(
    key: String,
    viewModel: RezkaViewModel
): LazyListState {
    val initialPos = remember(key) { viewModel.getScrollPosition(key) }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPos.index,
        initialFirstVisibleItemScrollOffset = initialPos.offset
    )

    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.saveScrollPosition(key, index, offset)
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
                viewModel.saveScrollPosition(key, 0, offset)
            }
    }

    return state
}
