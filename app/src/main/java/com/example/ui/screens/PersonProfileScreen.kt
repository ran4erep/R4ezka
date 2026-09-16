package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.RezkaCareerSection
import com.example.data.RezkaItem
import com.example.data.RezkaPerson
import com.example.data.RezkaService
import com.example.ui.theme.*
import com.example.ui.tv.dpadScrollable

sealed interface PersonState {
    object Loading : PersonState
    data class Success(val person: RezkaPerson) : PersonState
    data class Error(val message: String) : PersonState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonProfileScreen(
    name: String,
    url: String,
    onBack: () -> Unit,
    onNavigateToDetail: (RezkaItem) -> Unit,
    modifier: Modifier = Modifier,
    isTvMode: Boolean = false
) {
    var state by remember(url) { mutableStateOf<PersonState>(PersonState.Loading) }

    LaunchedEffect(url) {
        state = PersonState.Loading
        try {
            val person = RezkaService.getPersonProfile(url)
            state = PersonState.Success(person)
        } catch (e: Exception) {
            state = PersonState.Error(e.message ?: "Ошибка загрузки профиля")
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
                    text = name,
                    color = CinemaTextWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = CinemaTextWhite
                    )
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
                is PersonState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(48.dp))
                    }
                }
                is PersonState.Error -> {
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
                                state = PersonState.Loading
                                // Trigger retry
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaPrimary)
                        ) {
                            Text("Повторить", color = CinemaTextWhite)
                        }
                    }
                }
                is PersonState.Success -> {
                    val person = currentState.person
                    val context = LocalContext.current
                    val gridState = rememberLazyGridState()

                    // Выбранная вкладка карьеры: -1 = Все работы, 0..N = индекс роли (Актёр, Режиссёр, Продюсер и др.)
                    var selectedSectionIndex by remember(person.id) { mutableIntStateOf(-1) }

                    val displayedItems = remember(selectedSectionIndex, person) {
                        if (selectedSectionIndex == -1 || person.careerSections.isEmpty()) {
                            person.filmography
                        } else {
                            person.careerSections.getOrNull(selectedSectionIndex)?.items ?: person.filmography
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        state = gridState,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isTvMode) Modifier.dpadScrollable(gridState) else Modifier)
                    ) {
                        // Header Span covering biography + photo
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = CinemaDark),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        // 1. Photo (Avatar)
                                        SubcomposeAsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(person.photoUrl.ifEmpty { null })
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = person.name,
                                            contentScale = ContentScale.Crop,
                                            loading = {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(CinemaCard),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    CircularProgressIndicator(
                                                        color = CinemaPrimary,
                                                        strokeWidth = 2.dp,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            },
                                            error = {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(CinemaCard),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Person,
                                                        contentDescription = null,
                                                        tint = CinemaTextGray,
                                                        modifier = Modifier.size(40.dp)
                                                    )
                                                }
                                            },
                                            modifier = Modifier
                                                .width(115.dp)
                                                .height(170.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )

                                        Spacer(modifier = Modifier.width(14.dp))

                                        // 2. Info Column
                                        Column(modifier = Modifier.weight(1f)) {
                                            // Русское имя (крупно и ярко)
                                            Text(
                                                text = person.name,
                                                color = CinemaTextWhite,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                lineHeight = 24.sp
                                            )
                                            // Английское (оригинальное) имя строго под русским, менее заметно
                                            if (person.originalName.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = person.originalName,
                                                    color = CinemaTextGray.copy(alpha = 0.7f),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Normal,
                                                    letterSpacing = 0.2.sp
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(12.dp))

                                            // Таблица метаданных персоны
                                            person.info.forEach { (label, value) ->
                                                val metaIcon = when {
                                                    label.contains("карьер", ignoreCase = true) -> Icons.Default.WorkOutline
                                                    label.contains("рождения", ignoreCase = true) || label.contains("возраст", ignoreCase = true) -> Icons.Default.Cake
                                                    label.contains("место", ignoreCase = true) -> Icons.Default.Place
                                                    label.contains("рост", ignoreCase = true) -> Icons.Default.Straighten
                                                    label.contains("базе", ignoreCase = true) -> Icons.Default.Movie
                                                    label.contains("жанр", ignoreCase = true) -> Icons.Default.Category
                                                    else -> Icons.Default.Info
                                                }

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 3.dp),
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Icon(
                                                        imageVector = metaIcon,
                                                        contentDescription = null,
                                                        tint = CinemaTextGray,
                                                        modifier = Modifier
                                                            .padding(top = 2.dp)
                                                            .size(13.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "$label:",
                                                        color = CinemaTextGray,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        modifier = Modifier.width(105.dp)
                                                    )
                                                    Text(
                                                        text = value,
                                                        color = CinemaTextWhite,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Normal,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                // Заголовок фильмографии с количеством работ
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val totalCareerWorks = remember(person) {
                                        if (person.careerSections.isNotEmpty()) {
                                            person.careerSections.sumOf { it.items.size }
                                        } else {
                                            person.filmography.size
                                        }
                                    }

                                    val currentSectionTitle = if (selectedSectionIndex == -1) {
                                        "Все работы"
                                    } else {
                                        person.careerSections.getOrNull(selectedSectionIndex)?.title ?: "Работы"
                                    }

                                    Text(
                                        text = currentSectionTitle,
                                        color = CinemaTextWhite,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        color = CinemaPrimary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        val badgeCountText = if (selectedSectionIndex == -1) {
                                            "$totalCareerWorks работ"
                                        } else {
                                            "${displayedItems.size} работ"
                                        }
                                        Text(
                                            text = badgeCountText,
                                            color = CinemaPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                // Вкладки разделов карьеры (Актёр, Режиссёр, Продюсер, Сценарист и др.)
                                if (person.careerSections.size > 1) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Вкладка "Все"
                                        item {
                                            val totalSum = person.careerSections.sumOf { it.items.size }
                                            FilterChip(
                                                selected = selectedSectionIndex == -1,
                                                onClick = { selectedSectionIndex = -1 },
                                                label = {
                                                    Text("Все ($totalSum)")
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = CinemaPrimary,
                                                    selectedLabelColor = CinemaTextWhite,
                                                    containerColor = CinemaDark,
                                                    labelColor = CinemaTextGray
                                                ),
                                                border = FilterChipDefaults.filterChipBorder(
                                                    enabled = true,
                                                    selected = selectedSectionIndex == -1,
                                                    borderColor = CinemaBorder,
                                                    selectedBorderColor = CinemaPrimary
                                                ),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                        }

                                        // Отдельные вкладки для каждой роли
                                        itemsIndexed(person.careerSections) { index, section ->
                                            FilterChip(
                                                selected = selectedSectionIndex == index,
                                                onClick = { selectedSectionIndex = index },
                                                label = {
                                                    Text("${section.title} (${section.items.size})")
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = CinemaPrimary,
                                                    selectedLabelColor = CinemaTextWhite,
                                                    containerColor = CinemaDark,
                                                    labelColor = CinemaTextGray
                                                ),
                                                border = FilterChipDefaults.filterChipBorder(
                                                    enabled = true,
                                                    selected = selectedSectionIndex == index,
                                                    borderColor = CinemaBorder,
                                                    selectedBorderColor = CinemaPrimary
                                                ),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }

                        // Сетка фильмографии
                        if (displayedItems.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Список работ пуст",
                                        color = CinemaTextGray,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        } else {
                            items(displayedItems, key = { "${it.id}_${selectedSectionIndex}" }) { item ->
                                RezkaItemCard(
                                    item = item,
                                    onClick = { onNavigateToDetail(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
