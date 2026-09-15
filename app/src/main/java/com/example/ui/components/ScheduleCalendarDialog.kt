package com.example.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.ScheduleDateParser
import com.example.data.ScheduleItem
import com.example.ui.theme.*
import com.example.ui.tv.tvFocusableItem
import java.util.Calendar

/**
 * Структура для распарсенного элемента расписания с привязкой к дате календаря
 */
data class ParsedEpisode(
    val day: Int,
    val month: Int, // 1..12
    val year: Int,
    val item: ScheduleItem
)

private val MONTH_NAMES_RU = listOf(
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
)

private val WEEKDAY_NAMES_RU = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

@Composable
fun ScheduleCalendarDialog(
    schedule: List<ScheduleItem>,
    onDismiss: () -> Unit
) {
    val currentCal = remember { Calendar.getInstance() }
    val defaultCurrentYear = currentCal.get(Calendar.YEAR)
    val defaultCurrentMonth = currentCal.get(Calendar.MONTH) + 1
    val todayDateNum = remember { ScheduleDateParser.getTodayDateNum() }

    // Высокопроизводительный парсинг расписания с автоматическим определением факта выхода
    val parsedEpisodes = remember(schedule, todayDateNum) {
        val list = mutableListOf<ParsedEpisode>()
        for (item in schedule) {
            val dateStr = item.ruReleaseDate.ifEmpty { item.releaseDate }
            val parsed = ScheduleDateParser.parseDate(dateStr, defaultCurrentYear, todayDateNum)
                ?: ScheduleDateParser.parseDate(item.releaseDate, defaultCurrentYear, todayDateNum)

            val isReleased = when {
                parsed != null -> parsed.isReleased || item.isReleased
                item.isReleased -> true
                else -> false
            }

            val effectiveItem = if (item.isReleased != isReleased) item.copy(isReleased = isReleased) else item

            if (parsed != null) {
                list.add(ParsedEpisode(day = parsed.day, month = parsed.month, year = parsed.year, item = effectiveItem))
            } else {
                list.add(ParsedEpisode(day = 1, month = defaultCurrentMonth, year = defaultCurrentYear, item = effectiveItem))
            }
        }
        list
    }

    // Определение стартового месяца и года:
    val initialTarget = remember(parsedEpisodes) {
        val releasedEpisodes = parsedEpisodes.filter { it.item.isReleased }
        if (releasedEpisodes.isNotEmpty()) {
            releasedEpisodes.maxByOrNull { it.year * 10000 + it.month * 100 + it.day } ?: releasedEpisodes.last()
        } else {
            parsedEpisodes.firstOrNull() ?: ParsedEpisode(
                day = currentCal.get(Calendar.DAY_OF_MONTH),
                month = defaultCurrentMonth,
                year = defaultCurrentYear,
                item = ScheduleItem("", "", "")
            )
        }
    }

    var selectedYear by remember { mutableIntStateOf(initialTarget.year) }
    var selectedMonth by remember { mutableIntStateOf(initialTarget.month) } // 1..12

    val prevMonthFocusRequester = remember { FocusRequester() }

    // Расчет дней для отображаемого месяца
    val monthCal = remember(selectedYear, selectedMonth) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }

    val daysInMonth = remember(selectedYear, selectedMonth) {
        monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    // День недели первого дня месяца (1 = Воскресенье в Java Calendar -> 0 = Пн ... 6 = Вс)
    val firstDayOfWeekIndex = remember(selectedYear, selectedMonth) {
        val javaDay = monthCal.get(Calendar.DAY_OF_WEEK)
        (javaDay + 5) % 7
    }

    // Серии для выбранного месяца
    val monthEpisodes = remember(parsedEpisodes, selectedYear, selectedMonth) {
        parsedEpisodes.filter { it.year == selectedYear && it.month == selectedMonth }
    }

    val episodesByDay = remember(monthEpisodes) {
        monthEpisodes.groupBy { it.day }
    }

    val displayedEpisodesList = remember(monthEpisodes, schedule) {
        if (monthEpisodes.isNotEmpty()) {
            monthEpisodes.map { it.item }
        } else {
            parsedEpisodes.map { it.item }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_BACK ||
                         event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE)
                    ) {
                        onDismiss()
                        true
                    } else false
                },
            contentAlignment = Alignment.Center
        ) {
            val isLandscape = maxWidth > 680.dp || maxHeight < 580.dp

            Surface(
                modifier = Modifier
                    .fillMaxWidth(if (isLandscape) 0.92f else 0.95f)
                    .fillMaxHeight(if (isLandscape) 0.88f else 0.92f),
                shape = RoundedCornerShape(20.dp),
                color = CinemaDark,
                tonalElevation = 8.dp,
                border = BorderStroke(1.dp, CinemaCard)
            ) {
                if (isLandscape) {
                    // ---- ТЕЛЕВИЗИОННАЯ / ЛАНДШАФТНАЯ ВЕРСТКА: ДВЕ КОЛОНКИ РЯДОМ ----
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // ЛЕВАЯ КОЛОНКА: КАЛЕНДАРЬ
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                // Заголовок
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CalendarMonth,
                                        contentDescription = null,
                                        tint = CinemaPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "График выхода серий",
                                        color = CinemaTextWhite,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Переключение месяцев
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CinemaCard, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = CinemaSecondary.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .size(34.dp)
                                            .tvFocusableItem(
                                                onClick = {
                                                    if (selectedMonth == 1) {
                                                        selectedMonth = 12
                                                        selectedYear -= 1
                                                    } else {
                                                        selectedMonth -= 1
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                focusRequester = prevMonthFocusRequester
                                            )
                                            .testTag("calendar_prev_month_button")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Предыдущий месяц",
                                                tint = CinemaTextWhite,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = "${MONTH_NAMES_RU.getOrElse(selectedMonth - 1) { "" }} $selectedYear",
                                        color = CinemaTextWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (selectedYear != initialTarget.year || selectedMonth != initialTarget.month) {
                                            Surface(
                                                color = CinemaSecondary.copy(alpha = 0.5f),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .tvFocusableItem(
                                                        onClick = {
                                                            selectedYear = initialTarget.year
                                                            selectedMonth = initialTarget.month
                                                        },
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .testTag("calendar_today_button")
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.Today,
                                                        contentDescription = "К текущей серии",
                                                        tint = CinemaPrimary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Surface(
                                            color = CinemaSecondary.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .size(34.dp)
                                                .tvFocusableItem(
                                                    onClick = {
                                                        if (selectedMonth == 12) {
                                                            selectedMonth = 1
                                                            selectedYear += 1
                                                        } else {
                                                            selectedMonth += 1
                                                        }
                                                    },
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .testTag("calendar_next_month_button")
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                    contentDescription = "Следующий месяц",
                                                    tint = CinemaTextWhite,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Дни недели
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    WEEKDAY_NAMES_RU.forEachIndexed { idx, dayName ->
                                        val isWeekend = idx >= 5
                                        Text(
                                            text = dayName,
                                            color = if (isWeekend) CinemaAmber.copy(alpha = 0.9f) else CinemaTextGray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Календарная сетка месяца (без индикатора выбранного дня, компактная)
                                CalendarMonthGrid(
                                    firstDayOfWeekIndex = firstDayOfWeekIndex,
                                    daysInMonth = daysInMonth,
                                    episodesByDay = episodesByDay,
                                    cellHeight = 28.dp
                                )
                            }

                            // Легенда цветов
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(8.dp).background(Color(0xFF4CAF50), CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Серия вышла", color = CinemaTextWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)

                                Spacer(modifier = Modifier.width(20.dp))

                                Box(modifier = Modifier.size(8.dp).background(CinemaAmber, CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ожидается", color = CinemaTextWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        // ВЕРТИКАЛЬНЫЙ РАЗДЕЛИТЕЛЬ
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(CinemaCard)
                        )

                        // ПРАВАЯ КОЛОНКА: СПИСОК СЕРИЙ
                        Column(
                            modifier = Modifier
                                .weight(1.2f)
                                .fillMaxHeight()
                        ) {
                            // Заголовок списка серий и кнопка Закрыть
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val listTitle = if (monthEpisodes.isNotEmpty()) {
                                    "Серии за ${MONTH_NAMES_RU.getOrElse(selectedMonth - 1) { "" }} $selectedYear (${monthEpisodes.size})"
                                } else {
                                    "Все серии (${schedule.size})"
                                }

                                Text(
                                    text = listTitle,
                                    color = CinemaTextWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Surface(
                                    color = CinemaCard,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .size(32.dp)
                                        .tvFocusableItem(
                                            onClick = onDismiss,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .testTag("calendar_close_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Закрыть",
                                            tint = CinemaTextWhite,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Виртуализированный список серий с поддержкой D-Pad пульта
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .testTag("calendar_episodes_list"),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(
                                    items = displayedEpisodesList,
                                    key = { idx, sch -> "${sch.seasonEpisode}_${sch.releaseDate}_${sch.ruReleaseDate}_$idx" }
                                ) { idx, sch ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .tvFocusableItem(
                                                onClick = {},
                                                scaleFactor = 1.02f,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .testTag("calendar_episode_item_$idx"),
                                        colors = CardDefaults.cardColors(containerColor = CinemaCard),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = sch.seasonEpisode,
                                                    color = CinemaTextWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                if (sch.title.isNotEmpty()) {
                                                    Text(
                                                        text = sch.title,
                                                        color = CinemaTextGray,
                                                        fontSize = 11.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Column(horizontalAlignment = Alignment.End) {
                                                val dateText = sch.ruReleaseDate.ifEmpty { sch.releaseDate }
                                                Text(
                                                    text = dateText,
                                                    color = if (sch.isReleased) CinemaTextWhite else CinemaAmber,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                if (sch.ruReleaseDate.isNotEmpty() && sch.releaseDate.isNotEmpty() && sch.ruReleaseDate != sch.releaseDate) {
                                                    Text(
                                                        text = "Ориг: ${sch.releaseDate}",
                                                        color = CinemaTextGray,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Surface(
                                                color = if (sch.isReleased) Color(0xFF2E7D32).copy(alpha = 0.2f) else CinemaAmber.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (sch.isReleased) Color(0xFF4CAF50).copy(alpha = 0.6f) else CinemaAmber.copy(alpha = 0.6f)
                                                )
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    if (sch.isReleased) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.CheckCircle,
                                                            contentDescription = "Вышла",
                                                            tint = Color(0xFF4CAF50),
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Text(
                                                            text = "Вышла",
                                                            color = Color(0xFF81C784),
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    } else {
                                                        Icon(
                                                            imageVector = Icons.Outlined.Schedule,
                                                            contentDescription = "Ожидается",
                                                            tint = CinemaAmber,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Text(
                                                            text = "Ожидается",
                                                            color = CinemaAmber,
                                                            fontSize = 10.sp,
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
                } else {
                    // ---- ПОРТРЕТНАЯ ВЕРСТКА (ДЛЯ ТЕЛЕФОНА) ----
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = null,
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "График выхода серий",
                                        color = CinemaTextWhite,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Календарь релизов",
                                        color = CinemaTextGray,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectedYear != initialTarget.year || selectedMonth != initialTarget.month) {
                                    IconButton(
                                        onClick = {
                                            selectedYear = initialTarget.year
                                            selectedMonth = initialTarget.month
                                        },
                                        modifier = Modifier.size(32.dp).background(CinemaCard, CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Today,
                                            contentDescription = "К текущей серии",
                                            tint = CinemaPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(32.dp).background(CinemaCard, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Закрыть",
                                        tint = CinemaTextWhite,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Переключение месяцев
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CinemaCard, RoundedCornerShape(10.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (selectedMonth == 1) {
                                        selectedMonth = 12
                                        selectedYear -= 1
                                    } else {
                                        selectedMonth -= 1
                                    }
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Предыдущий месяц",
                                    tint = CinemaTextWhite,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Text(
                                text = "${MONTH_NAMES_RU.getOrElse(selectedMonth - 1) { "" }} $selectedYear",
                                color = CinemaTextWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )

                            IconButton(
                                onClick = {
                                    if (selectedMonth == 12) {
                                        selectedMonth = 1
                                        selectedYear += 1
                                    } else {
                                        selectedMonth += 1
                                    }
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Следующий месяц",
                                    tint = CinemaTextWhite,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Дни недели
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            WEEKDAY_NAMES_RU.forEachIndexed { idx, dayName ->
                                val isWeekend = idx >= 5
                                Text(
                                    text = dayName,
                                    color = if (isWeekend) CinemaAmber.copy(alpha = 0.9f) else CinemaTextGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Календарная сетка месяца
                        CalendarMonthGrid(
                            firstDayOfWeekIndex = firstDayOfWeekIndex,
                            daysInMonth = daysInMonth,
                            episodesByDay = episodesByDay,
                            cellHeight = 28.dp
                        )

                        // Легенда
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(7.dp).background(Color(0xFF4CAF50), CircleShape))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Серия вышла", color = CinemaTextWhite, fontSize = 10.sp, fontWeight = FontWeight.Medium)

                            Spacer(modifier = Modifier.width(16.dp))

                            Box(modifier = Modifier.size(7.dp).background(CinemaAmber, CircleShape))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Ожидается", color = CinemaTextWhite, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = CinemaCard,
                            thickness = 0.8.dp
                        )

                        // Список серий
                        val listTitle = if (monthEpisodes.isNotEmpty()) {
                            "Серии за ${MONTH_NAMES_RU.getOrElse(selectedMonth - 1) { "" }} $selectedYear (${monthEpisodes.size})"
                        } else {
                            "Все серии (${schedule.size})"
                        }

                        Text(
                            text = listTitle,
                            color = CinemaTextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(
                                items = displayedEpisodesList,
                                key = { idx, sch -> "${sch.seasonEpisode}_${sch.releaseDate}_${sch.ruReleaseDate}_$idx" }
                            ) { _, sch ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = CinemaCard),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = sch.seasonEpisode,
                                                color = CinemaTextWhite,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (sch.title.isNotEmpty()) {
                                                Text(
                                                    text = sch.title,
                                                    color = CinemaTextGray,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Column(horizontalAlignment = Alignment.End) {
                                            val dateText = sch.ruReleaseDate.ifEmpty { sch.releaseDate }
                                            Text(
                                                text = dateText,
                                                color = if (sch.isReleased) CinemaTextWhite else CinemaAmber,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Surface(
                                            color = if (sch.isReleased) Color(0xFF2E7D32).copy(alpha = 0.2f) else CinemaAmber.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = if (sch.isReleased) "Вышла" else "Ожидается",
                                                color = if (sch.isReleased) Color(0xFF81C784) else CinemaAmber,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
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
 * Компактная сетка месяца календаря без индикатора выбранного дня.
 * Высота ячеек фиксирована для предотвращения выхода за границы экрана ТВ.
 */
@Composable
private fun CalendarMonthGrid(
    firstDayOfWeekIndex: Int,
    daysInMonth: Int,
    episodesByDay: Map<Int, List<ParsedEpisode>>,
    cellHeight: androidx.compose.ui.unit.Dp
) {
    val totalCells = firstDayOfWeekIndex + daysInMonth
    val totalRows = (totalCells + 6) / 7

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CinemaSecondary.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (row in 0 until totalRows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (col in 0..6) {
                    val cellIndex = row * 7 + col
                    val dayNum = cellIndex - firstDayOfWeekIndex + 1

                    if (dayNum in 1..daysInMonth) {
                        val episodesOnThisDay = episodesByDay[dayNum] ?: emptyList()
                        val hasEpisode = episodesOnThisDay.isNotEmpty()
                        val hasReleased = episodesOnThisDay.any { it.item.isReleased }

                        val bg = when {
                            hasEpisode && hasReleased -> Color(0xFF2E7D32).copy(alpha = 0.35f)
                            hasEpisode -> CinemaAmber.copy(alpha = 0.22f)
                            else -> Color.Transparent
                        }

                        val border = when {
                            hasEpisode && hasReleased -> Color(0xFF4CAF50).copy(alpha = 0.7f)
                            hasEpisode -> CinemaAmber.copy(alpha = 0.7f)
                            else -> Color.Transparent
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(cellHeight)
                                .padding(1.5.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(bg)
                                .border(0.8.dp, border, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = dayNum.toString(),
                                    color = if (hasEpisode) CinemaTextWhite else CinemaTextGray,
                                    fontSize = 11.sp,
                                    fontWeight = if (hasEpisode) FontWeight.Bold else FontWeight.Normal
                                )

                                if (hasEpisode) {
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(if (hasReleased) Color(0xFF4CAF50) else CinemaAmber)
                                    )
                                }
                            }
                        }
                    } else {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .height(cellHeight)
                                .padding(1.5.dp)
                        )
                    }
                }
            }
        }
    }
}
