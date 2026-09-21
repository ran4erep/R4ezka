package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.example.ui.util.rememberSavedLazyListState
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
import com.example.ui.theme.*
import com.example.ui.tv.*

@Composable
fun HistoryScreen(
    viewModel: RezkaViewModel,
    onNavigateToDetail: (RezkaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val historyList by viewModel.aggregatedWatchHistory.collectAsStateWithLifecycle()

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
                Text(
                    text = "ИСТОРИЯ",
                    color = CinemaPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Прогресс ваших просмотров",
                    color = CinemaTextGray,
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
        ) {
            if (historyList.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
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
            } else {
                val listState = rememberSavedLazyListState("history", viewModel)
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .dpadScrollable(listState)
                        .testTag("history_list")
                ) {
                    items(
                        items = historyList,
                        key = { it.itemId }
                    ) { history ->
                        HistoryCardItem(
                            history = history,
                            onClick = {
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
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progressFraction = remember(history.totalProgressFraction) {
        history.totalProgressFraction.coerceIn(0f, 1f)
    }
    val progressPercentage = remember(progressFraction) {
        (progressFraction * 100f).roundToInt()
    }
    val isFullyWatched = remember(history.isSeries, history.watchedEpisodesCount, history.totalEpisodesCount, progressPercentage, history.totalProgressFraction) {
        if (history.isSeries) {
            (history.watchedEpisodesCount >= history.totalEpisodesCount && history.totalEpisodesCount > 0) || progressPercentage >= 100
        } else {
            progressPercentage >= 100 || history.totalProgressFraction >= 0.85f
        }
    }

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
                .tvFocusableItem(
                    onClick = onClick, 
                    scaleFactor = 1.015f, 
                    shape = RoundedCornerShape(12.dp)
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
                        Text(
                            text = "Сезон ${history.latestSeason}, Серия ${history.latestEpisode}",
                            color = CinemaPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = "Просмотрено ${history.watchedEpisodesCount} из ${history.totalEpisodesCount} серий",
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
                    )

                    Text(
                        text = if (history.isSeries) {
                            if (isFullyWatched) "100% — Все серии просмотрены" else "$progressPercentage% общего прогресса"
                        } else {
                            if (isFullyWatched) "100% просмотрено" else "$progressPercentage% просмотрено"
                        },
                        color = if (isFullyWatched) CinemaGreen else CinemaTextGray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Продолговатая аккуратная кнопка удаления из истории под карточкой
        Surface(
            color = CinemaDark,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .tvFocusableItem(
                    onClick = onDelete,
                    scaleFactor = 1.01f,
                    focusedBorderColor = CinemaPrimary,
                    shape = RoundedCornerShape(8.dp)
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
                    contentDescription = null,
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
    }
}
