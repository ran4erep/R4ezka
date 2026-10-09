package com.example.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.data.CountryFlags
import com.example.data.CountryItem
import com.example.ui.haptics.HapticEngine
import com.example.ui.haptics.HapticType
import com.example.ui.theme.*
import com.example.ui.tv.requestFocusSafe
import com.example.ui.tv.tvFocusableItem
import kotlinx.coroutines.launch

/**
 * Высокопроизводительная фильтрация списка стран по поисковому запросу.
 * Выполняется в памяти за микросекунды с предвычисленными поисковыми ключами без лишних аллокаций.
 */
fun filterCountryItems(countries: List<CountryItem>, query: String): List<CountryItem> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return countries

    val cleanSearch = CountryFlags.cleanCountryName(trimmed).lowercase()
    val rawSearchLower = trimmed.lowercase()
    val isSame = cleanSearch == rawSearchLower

    return countries.filter { item ->
        val key = item.searchKey
        if (isSame) {
            key.contains(cleanSearch)
        } else {
            key.contains(cleanSearch) || key.contains(rawSearchLower)
        }
    }
}

/**
 * Стабильный PopupPositionProvider, привязывающий выпадающий список строго под кнопкой-триггером
 * и предотвращающий хаотичные скачки меню вверх при открытии экранной клавиатуры.
 */
class StableDropdownPositionProvider(
    private val density: Density,
    private val verticalOffsetDp: Int = 4
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val verticalOffsetPx = with(density) { verticalOffsetDp.dp.roundToPx() }
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val spaceBelow = windowSize.height - anchorBounds.bottom
        val y = if (spaceBelow >= popupContentSize.height + verticalOffsetPx || spaceBelow >= 220) {
            anchorBounds.bottom + verticalOffsetPx
        } else {
            // Если снизу критически мало места (например, мобильная клавиатура), выравниваем с запасом снизу
            (windowSize.height - popupContentSize.height - with(density) { 8.dp.roundToPx() }).coerceAtLeast(8)
        }
        return IntOffset(x, y)
    }
}

/**
 * Специализированный выпадающий список выбора страны для ТВ-интерфейса и пульта.
 *
 * Архитектурные особенности решения:
 * 1. Фиксированная шапка (Pinned Header):
 *    - Пункт «Все страны» всегда сверху.
 *    - Под ним поле «Поиск...» с подсветкой фокуса пульта и активацией ввода по клику.
 *    Шапка НИКОГДА не скроллится и не уезжает вверх!
 * 2. Отдельный независимый список стран:
 *    - При изменении поискового запроса searchQuery скролл автоматически сбрасывается в 0,
 *      благодаря чему первая найденная страна ВСЕГДА находится ровно под полем поиска.
 * 3. Навигация с пульта:
 *    - По стрелке вниз из «Все страны» -> фокус на поле поиска.
 *    - Нажатие OK на поле поиска -> активация экранной клавиатуры.
 *    - Нажатие Enter или стрелки вниз -> скрытие клавиатуры и переход на первую отфильтрованную страну.
 *    - По стрелке вверх из первой страны -> возврат фокуса на поле поиска.
 */
@Composable
fun TvCountryDropdown(
    selectedCountry: CountryItem,
    countriesList: List<CountryItem>,
    onCountrySelected: (CountryItem) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isEditingSearch by remember { mutableStateOf(false) }

    val triggerRequester = focusRequester ?: remember { FocusRequester() }
    val allCountriesRequester = remember { FocusRequester() }
    val searchFieldRequester = remember { FocusRequester() }
    val searchInputRequester = remember { FocusRequester() }
    val firstCountryRequester = remember { FocusRequester() }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val allCountriesItem = remember(countriesList) {
        countriesList.firstOrNull { it.query.isEmpty() } ?: CountryItem("Все страны", "")
    }

    val rawCountries = remember(countriesList) {
        countriesList.filter { it.query.isNotEmpty() }
    }

    val filteredCountries = remember(rawCountries, searchQuery) {
        if (searchQuery.isBlank()) {
            rawCountries
        } else {
            filterCountryItems(rawCountries, searchQuery)
        }
    }

    val countriesLazyListState = rememberLazyListState()

    // Как только меняется поисковый запрос, МГНОВЕННО сбрасываем скролл списка в самое начало
    LaunchedEffect(searchQuery) {
        countriesLazyListState.scrollToItem(0)
    }

    // При закрытии выпадающего списка очищаем поиск
    LaunchedEffect(expanded) {
        if (!expanded) {
            searchQuery = ""
            isEditingSearch = false
        }
    }

    Box(modifier = modifier) {
        // Триггерная кнопка выбора страны в панели фильтров ТВ
        Surface(
            color = CinemaDark,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                1.dp,
                if (expanded) CinemaPrimary else Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                if (onUp != null) { onUp(); true } else false
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (onDown != null) { onDown(); true } else false
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (onLeft != null) { onLeft(); true } else false
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (onRight != null) { onRight(); true } else false
                            }
                            else -> false
                        }
                    } else false
                }
                .tvFocusableItem(
                    onClick = { expanded = !expanded },
                    scaleFactor = 1.04f,
                    shape = RoundedCornerShape(10.dp),
                    focusRequester = triggerRequester
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedCountry.name,
                    color = CinemaTextWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = CinemaPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (expanded) {
            val positionProvider = remember(density) {
                StableDropdownPositionProvider(density, verticalOffsetDp = 4)
            }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = {
                    expanded = false
                    isEditingSearch = false
                    searchQuery = ""
                    coroutineScope.launch {
                        triggerRequester.requestFocusSafe()
                    }
                },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    color = CinemaDark,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .widthIn(min = 230.dp, max = 290.dp)
                        .heightIn(max = 380.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        // =================== ФИКСИРОВАННАЯ ШАПКА (НЕ СКРОЛЛИТСЯ) ===================

                        // 1. Пункт «Все страны» (всегда в самом верху)
                        val isAllSelected = selectedCountry.query.isEmpty()
                        Surface(
                            color = if (isAllSelected) CinemaPrimary.copy(alpha = 0.15f) else Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                        when (keyEvent.nativeKeyEvent.keyCode) {
                                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                searchFieldRequester.requestFocusSafe()
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                .tvFocusableItem(
                                    onClick = {
                                        expanded = false
                                        searchQuery = ""
                                        isEditingSearch = false
                                        onCountrySelected(allCountriesItem)
                                        coroutineScope.launch {
                                            triggerRequester.requestFocusSafe()
                                        }
                                    },
                                    scaleFactor = 1.02f,
                                    shape = RoundedCornerShape(6.dp),
                                    focusRequester = allCountriesRequester
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = allCountriesItem.name,
                                    color = if (isAllSelected) CinemaPrimary else CinemaTextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                if (isAllSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // 2. Пункт: «Поиск...» строго под «Все страны»
                        Surface(
                            color = CinemaCard,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isEditingSearch || searchQuery.isNotEmpty()) CinemaPrimary else CinemaBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .height(38.dp)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                        when (keyEvent.nativeKeyEvent.keyCode) {
                                            AndroidKeyEvent.KEYCODE_BACK -> {
                                                if (isEditingSearch) {
                                                    isEditingSearch = false
                                                    keyboardController?.hide()
                                                    searchFieldRequester.requestFocusSafe()
                                                    true
                                                } else false
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                                if (!isEditingSearch) {
                                                    allCountriesRequester.requestFocusSafe()
                                                    true
                                                } else false
                                            }
                                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                if (!isEditingSearch && filteredCountries.isNotEmpty()) {
                                                    firstCountryRequester.requestFocusSafe()
                                                    true
                                                } else false
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                .then(
                                    if (!isEditingSearch) {
                                        Modifier.tvFocusableItem(
                                            onClick = {
                                                isEditingSearch = true
                                            },
                                            scaleFactor = 1.02f,
                                            focusedBorderWidth = 2.dp,
                                            shape = RoundedCornerShape(8.dp),
                                            focusRequester = searchFieldRequester
                                        )
                                    } else Modifier
                                )
                                .testTag("tv_country_search_box")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Поиск страны",
                                    tint = if (isEditingSearch || searchQuery.isNotEmpty()) CinemaPrimary else CinemaTextGray,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (!isEditingSearch) {
                                        Text(
                                            text = if (searchQuery.isNotEmpty()) searchQuery else "Поиск страны...",
                                            color = if (searchQuery.isNotEmpty()) CinemaTextWhite else CinemaMuted,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (searchQuery.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    } else {
                                        LaunchedEffect(Unit) {
                                            searchInputRequester.requestFocusSafe()
                                            keyboardController?.show()
                                        }

                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                color = CinemaTextWhite,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            ),
                                            cursorBrush = SolidColor(CinemaPrimary),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                            keyboardActions = KeyboardActions(
                                                onSearch = {
                                                    isEditingSearch = false
                                                    keyboardController?.hide()
                                                    focusManager.clearFocus()
                                                    if (filteredCountries.isNotEmpty()) {
                                                        firstCountryRequester.requestFocusSafe()
                                                    }
                                                }
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(searchInputRequester)
                                                .onKeyEvent { keyEvent ->
                                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                                        when (keyEvent.nativeKeyEvent.keyCode) {
                                                            AndroidKeyEvent.KEYCODE_BACK -> {
                                                                isEditingSearch = false
                                                                keyboardController?.hide()
                                                                searchFieldRequester.requestFocusSafe()
                                                                true
                                                            }
                                                            AndroidKeyEvent.KEYCODE_ENTER,
                                                            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                                                            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                                                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                                                isEditingSearch = false
                                                                keyboardController?.hide()
                                                                focusManager.clearFocus()
                                                                if (filteredCountries.isNotEmpty()) {
                                                                    firstCountryRequester.requestFocusSafe()
                                                                }
                                                                true
                                                            }
                                                            else -> false
                                                        }
                                                    } else false
                                                },
                                            decorationBox = { innerTextField ->
                                                if (searchQuery.isEmpty()) {
                                                    Text(
                                                        text = "Введите страну...",
                                                        color = CinemaMuted,
                                                        fontSize = 11.5.sp
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        )
                                    }
                                }

                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            searchQuery = ""
                                            isEditingSearch = false
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Очистить",
                                            tint = CinemaTextGray,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(
                            color = CinemaBorder.copy(alpha = 0.5f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        // =================== СПИСОК ОТФИЛЬТРОВАННЫХ СТРАН ===================
                        // Имеет собственный независимый скролл и всегда отображается строго под строкой поиска
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                        ) {
                            if (filteredCountries.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Страна не найдена",
                                        color = CinemaTextGray,
                                        fontSize = 11.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    state = countriesLazyListState,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    itemsIndexed(
                                        items = filteredCountries,
                                        key = { _, item -> item.query.ifEmpty { item.name } }
                                    ) { index, countryItem ->
                                        val isSelected = countryItem.query == selectedCountry.query
                                        val itemRequester = if (index == 0) firstCountryRequester else null

                                        Surface(
                                            color = if (isSelected) CinemaPrimary.copy(alpha = 0.15f) else Color.Transparent,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                .onKeyEvent { keyEvent ->
                                                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                                                        if (index == 0) {
                                                            searchFieldRequester.requestFocusSafe()
                                                            true
                                                        } else false
                                                    } else false
                                                }
                                                .tvFocusableItem(
                                                    onClick = {
                                                        expanded = false
                                                        searchQuery = ""
                                                        isEditingSearch = false
                                                        onCountrySelected(countryItem)
                                                        coroutineScope.launch {
                                                            triggerRequester.requestFocusSafe()
                                                        }
                                                    },
                                                    scaleFactor = 1.02f,
                                                    shape = RoundedCornerShape(6.dp),
                                                    focusRequester = itemRequester
                                                )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = countryItem.name,
                                                    color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = CinemaPrimary,
                                                        modifier = Modifier.size(16.dp)
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
    }
}

/**
 * Выпадающий список выбора страны для мобильного / планшетного интерфейса.
 * Поддерживает пункт «Все страны», под ним закреплённое поле «Поиск...» с живой фильтрацией,
 * автоматическим сбросом скролла и плавной прокруткой без улетания элементов вверх.
 */
@Composable
fun RezkaCountryDropdown(
    label: String = "Страна",
    options: List<CountryItem>,
    selectedOption: CountryItem,
    onOptionSelected: (CountryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val haptic = HapticEngine.get()
    val density = LocalDensity.current

    val allCountriesItem = remember(options) {
        options.firstOrNull { it.query.isEmpty() } ?: CountryItem("Все страны", "")
    }

    val rawCountries = remember(options) {
        options.filter { it.query.isNotEmpty() }
    }

    val filteredCountries = remember(rawCountries, searchQuery) {
        if (searchQuery.isBlank()) {
            rawCountries
        } else {
            filterCountryItems(rawCountries, searchQuery)
        }
    }

    val countriesLazyListState = rememberLazyListState()

    // При вводе поискового запроса сбрасываем скролл в 0, чтобы результаты отображались сразу под полем ввода
    LaunchedEffect(searchQuery) {
        countriesLazyListState.scrollToItem(0)
    }

    LaunchedEffect(expanded) {
        if (!expanded) {
            searchQuery = ""
        }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(CinemaDark)
                .clickable {
                    haptic.perform(HapticType.SELECTION)
                    expanded = true
                }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedOption.name,
                color = CinemaTextWhite,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = CinemaPrimary,
                modifier = Modifier.size(16.dp)
            )
        }

        if (expanded) {
            val positionProvider = remember(density) {
                StableDropdownPositionProvider(density, verticalOffsetDp = 4)
            }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = {
                    expanded = false
                    searchQuery = ""
                },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    color = CinemaDark,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CinemaPrimary.copy(alpha = 0.5f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 290.dp)
                        .heightIn(max = 360.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        // 1. Фиксированный пункт «Все страны» (всегда сверху)
                        val isAllSelected = selectedOption.query.isEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.perform(HapticType.SELECTION)
                                    onOptionSelected(allCountriesItem)
                                    expanded = false
                                    searchQuery = ""
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = allCountriesItem.name,
                                color = if (isAllSelected) CinemaPrimary else CinemaTextWhite,
                                fontSize = 12.sp,
                                fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            if (isAllSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // 2. Закрепленное поле «Поиск...» строго под «Все страны»
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = CinemaTextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                cursorBrush = SolidColor(CinemaPrimary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .background(CinemaCard, RoundedCornerShape(8.dp))
                                    .border(
                                        1.dp,
                                        if (searchQuery.isNotEmpty()) CinemaPrimary else CinemaBorder,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp),
                                decorationBox = { innerTextField ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "Поиск",
                                            tint = CinemaPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(modifier = Modifier.weight(1f)) {
                                            if (searchQuery.isEmpty()) {
                                                Text(
                                                    text = "Поиск страны...",
                                                    color = CinemaMuted,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            innerTextField()
                                        }
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(
                                                onClick = { searchQuery = "" },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Очистить",
                                                    tint = CinemaTextGray,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        HorizontalDivider(
                            color = CinemaBorder.copy(alpha = 0.5f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        // 3. Список стран (прокручиваемый независимо)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                        ) {
                            if (filteredCountries.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Страна не найдена",
                                        color = CinemaTextGray,
                                        fontSize = 11.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    state = countriesLazyListState,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(
                                        items = filteredCountries,
                                        key = { it.query.ifEmpty { it.name } }
                                    ) { countryItem ->
                                        val isSelected = countryItem.query == selectedOption.query
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    haptic.perform(HapticType.SELECTION)
                                                    onOptionSelected(countryItem)
                                                    expanded = false
                                                    searchQuery = ""
                                                }
                                                .padding(horizontal = 12.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = countryItem.name,
                                                color = if (isSelected) CinemaPrimary else CinemaTextWhite,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = CinemaPrimary,
                                                    modifier = Modifier.size(16.dp)
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
}
