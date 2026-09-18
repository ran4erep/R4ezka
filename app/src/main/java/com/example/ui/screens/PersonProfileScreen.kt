package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.example.ui.tv.requestFocusSafe
import com.example.ui.tv.tvFocusableItem

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

    BackHandler(onBack = onBack)

    if (isTvMode) {
        when (val currentState = state) {
            is PersonState.Loading -> {
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .background(CinemaBlack),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CinemaPrimary, modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
                }
            }
            is PersonState.Error -> {
                Column(
                    modifier = modifier
                        .fillMaxSize()
                        .background(CinemaBlack)
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
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Surface(
                            color = CinemaPrimary,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.tvFocusableItem(
                                onClick = {
                                    state = PersonState.Loading
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        ) {
                            Text("Повторить", color = CinemaTextWhite, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                        }
                        Surface(
                            color = CinemaCard,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, CinemaBorder),
                            modifier = Modifier.tvFocusableItem(
                                onClick = onBack,
                                shape = RoundedCornerShape(8.dp)
                            )
                        ) {
                            Text("Назад", color = CinemaTextWhite, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                        }
                    }
                }
            }
            is PersonState.Success -> {
                TvPersonProfileContent(
                    person = currentState.person,
                    onBack = onBack,
                    onNavigateToDetail = onNavigateToDetail,
                    modifier = modifier
                )
            }
        }
        return
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

/**
 * Высокопроизводительный двухпанельный интерфейс фильмографии персоны для Android TV.
 * - Слева: профиль персоны, фото, метаданные и кнопка "Назад к фильму" с автофокусом.
 * - Справа: интерактивные фильтры ролей (чипы с ТВ-фокусом) и кинематографичная сетка работ.
 */
@Composable
private fun TvPersonProfileContent(
    person: RezkaPerson,
    onBack: () -> Unit,
    onNavigateToDetail: (RezkaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val backFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        backFocusRequester.requestFocusSafe()
    }

    var selectedSectionIndex by remember(person.id) { mutableIntStateOf(-1) }
    val displayedItems = remember(selectedSectionIndex, person) {
        if (selectedSectionIndex == -1 || person.careerSections.isEmpty()) {
            person.filmography
        } else {
            person.careerSections.getOrNull(selectedSectionIndex)?.items ?: person.filmography
        }
    }

    val totalCareerWorks = remember(person) {
        if (person.careerSections.isNotEmpty()) {
            person.careerSections.sumOf { it.items.size }
        } else {
            person.filmography.size
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .padding(16.dp)
    ) {
        // ---- ЛЕВАЯ ПАНЕЛЬ: Инфо о персоне (компактное умное расположение без прокрутки) ----
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .padding(end = 16.dp)
        ) {
            // Кнопка назад к фильму с автофокусом для пульта
            Surface(
                color = CinemaCard,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CinemaBorder),
                modifier = Modifier
                    .focusRequester(backFocusRequester)
                    .tvFocusableItem(
                        onClick = onBack,
                        scaleFactor = 1.04f,
                        shape = RoundedCornerShape(10.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = CinemaTextWhite,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Назад к фильму",
                        color = CinemaTextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Карточка с портретом, именем и бейджем работ
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CinemaDark),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CinemaBorder)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        // 1. Компактное портретное фото
                        if (person.photoUrl.isNotEmpty()) {
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, CinemaBorder),
                                modifier = Modifier
                                    .width(80.dp)
                                    .aspectRatio(0.7f)
                            ) {
                                AsyncImage(
                                    model = person.photoUrl,
                                    contentDescription = person.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        // 2. Имя и статистика
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = person.name,
                                color = CinemaTextWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (person.originalName.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = person.originalName,
                                    color = CinemaTextGray,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Surface(
                                color = CinemaPrimary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Всего $totalCareerWorks работ",
                                    color = CinemaPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    // 3. Метаданные персоны
                    if (person.info.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = CinemaBorder, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(6.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            person.info.forEach { (label, value) ->
                                val metaIcon = when {
                                    label.contains("карьер", ignoreCase = true) -> Icons.Default.WorkOutline
                                    label.contains("рост", ignoreCase = true) -> Icons.Default.Straighten
                                    label.contains("рождения", ignoreCase = true) || label.contains("возраст", ignoreCase = true) -> Icons.Default.Cake
                                    label.contains("место", ignoreCase = true) -> Icons.Default.Place
                                    label.contains("жанр", ignoreCase = true) -> Icons.Default.Category
                                    label.contains("фильм", ignoreCase = true) || label.contains("базе", ignoreCase = true) -> Icons.Default.Movie
                                    else -> Icons.Default.Info
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = metaIcon,
                                        contentDescription = null,
                                        tint = CinemaTextGray,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "$label:",
                                        color = CinemaTextGray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.width(90.dp)
                                    )
                                    Text(
                                        text = value,
                                        color = CinemaTextWhite,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Normal,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- ПРАВАЯ ПАНЕЛЬ: Фильмография персоны ----
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            val currentSectionTitle = if (selectedSectionIndex == -1) {
                "Все работы"
            } else {
                person.careerSections.getOrNull(selectedSectionIndex)?.title ?: "Работы"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentSectionTitle,
                    color = CinemaTextWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${displayedItems.size} фильмов",
                    color = CinemaTextGray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Фильтры категорий ролей (Актёр, Режиссёр, Сценарист...)
            if (person.careerSections.size > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        val isSelected = selectedSectionIndex == -1
                        Surface(
                            color = if (isSelected) CinemaPrimary else CinemaDark,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) CinemaPrimary else CinemaBorder
                            ),
                            modifier = Modifier.tvFocusableItem(
                                onClick = { selectedSectionIndex = -1 },
                                scaleFactor = 1.05f,
                                shape = RoundedCornerShape(8.dp)
                            )
                        ) {
                            Text(
                                text = "Все ($totalCareerWorks)",
                                color = if (isSelected) CinemaTextWhite else CinemaTextGray,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    itemsIndexed(person.careerSections) { index, section ->
                        val isSelected = selectedSectionIndex == index
                        Surface(
                            color = if (isSelected) CinemaPrimary else CinemaDark,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) CinemaPrimary else CinemaBorder
                            ),
                            modifier = Modifier.tvFocusableItem(
                                onClick = { selectedSectionIndex = index },
                                scaleFactor = 1.05f,
                                shape = RoundedCornerShape(8.dp)
                            )
                        ) {
                            Text(
                                text = "${section.title} (${section.items.size})",
                                color = if (isSelected) CinemaTextWhite else CinemaTextGray,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Сетка фильмов
            if (displayedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Список работ пуст",
                        color = CinemaTextGray,
                        fontSize = 14.sp
                    )
                }
            } else {
                val tvGridState = rememberLazyGridState()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    state = tvGridState,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .dpadScrollable(tvGridState)
                        .testTag("person_tv_grid")
                ) {
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

