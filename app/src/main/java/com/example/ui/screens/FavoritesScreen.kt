package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.ui.tv.dpadScrollable
import com.example.ui.tv.tvFocusableItem

@Composable
fun FavoritesScreen(
    viewModel: RezkaViewModel,
    onNavigateToDetail: (RezkaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val favorites by viewModel.favorites.collectAsState()
    val cardGridMode by viewModel.cardGridMode.collectAsState()
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
        ) {
            Text(
                text = "ИЗБРАННОЕ",
                color = CinemaPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "Сохраненные фильмы и сериалы",
                color = CinemaTextGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (favorites.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
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
                val isDense = columnsCount >= 5
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columnsCount),
                    state = gridState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (columnsCount >= 6) 8.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (columnsCount >= 6) 10.dp else 16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .dpadScrollable(gridState)
                        .testTag("favorites_grid")
                ) {
                    items(
                        items = favorites,
                        key = { it.id }
                    ) { fav ->
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                        ) {
                            RezkaItemCard(
                                item = item,
                                columnsCount = columnsCount,
                                onClick = { onNavigateToDetail(item) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Spacer(modifier = Modifier.height(if (isDense) 4.dp else 8.dp))
                            
                            Surface(
                                onClick = { viewModel.removeFavorite(fav.id) },
                                color = CinemaDark,
                                shape = RoundedCornerShape(if (isDense) 6.dp else 8.dp),
                                border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.8f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (columnsCount >= 7) 28.dp else if (isDense) 32.dp else 38.dp)
                                    .tvFocusableItem(
                                        onClick = { viewModel.removeFavorite(fav.id) },
                                        scaleFactor = 1.04f,
                                        focusedBorderColor = CinemaPrimary,
                                        shape = RoundedCornerShape(if (isDense) 6.dp else 8.dp)
                                    )
                                    .testTag("remove_favorite_${fav.id}")
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
                                        modifier = Modifier.size(if (isDense) 14.dp else 16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(if (isDense) 4.dp else 6.dp))
                                    Text(
                                        text = "Удалить",
                                        color = CinemaTextWhite,
                                        fontSize = if (columnsCount >= 7) 9.sp else if (isDense) 10.sp else 11.sp,
                                        fontWeight = FontWeight.Bold
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
