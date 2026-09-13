package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.ParsedScheduleDate
import com.example.data.ScheduleDateParser
import com.example.data.ScheduleItem
import com.example.ui.theme.*
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
                // Если дату не удалось определить, сохраняем для общего списка
                list.add(ParsedEpisode(day = 1, month = defaultCurrentMonth, year = defaultCurrentYear, item = effectiveItem))
            }
        }
        list
    }

    // Определение стартового месяца и года:
    // 1. Если есть вышедшие серии (дата <= сегодня), открываем на месяце и годе ПОСЛЕДНЕЙ ВЫШЕДШЕЙ (доступной) серии.
    // 2. Если сериал старый и все серии вышли — открываем на месяце и годе последней серии.
    // 3. Если серии ожидаются в будущем — открываем не на будущих месяцах, а на месяце текущей последней вышедшей серии.
    // 4. Если еще ни одна серия не вышла (все в будущем) — открываем на первой ожидаемой серии.
    val initialTarget = remember(parsedEpisodes) {
        val releasedEpisodes = parsedEpisodes.filter { it.item.isReleased }
        if (releasedEpisodes.isNotEmpty()) {
            // Текущая последняя вышедшая доступная серия (максимальная по дате среди вышедших)
            releasedEpisodes.maxByOrNull { it.year * 10000 + it.month * 100 + it.day } ?: releasedEpisodes.last()
        } else {
            // Ни одна серия еще не вышла — берем первую ожидаемую серию
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
    var selectedDay by remember { mutableStateOf<Int?>(initialTarget.day) }

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

    // День недели первого дня месяца (1 = Воскресенье в Java Calendar, преобразуем к 0 = Пн ... 6 = Вс)
    val firstDayOfWeekIndex = remember(selectedYear, selectedMonth) {
        val javaDay = monthCal.get(Calendar.DAY_OF_WEEK) // 1=Sun, 2=Mon...
        (javaDay + 5) % 7 // 0 = Mon, 6 = Sun
    }

    // Серии для выбранного месяца (кэш для быстрого поиска)
    val monthEpisodes = remember(parsedEpisodes, selectedYear, selectedMonth) {
        parsedEpisodes.filter { it.year == selectedYear && it.month == selectedMonth }
    }

    val episodesByDay = remember(monthEpisodes) {
        monthEpisodes.groupBy { it.day }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(20.dp),
            color = CinemaDark,
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, CinemaCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header: Заголовок, кнопка возврата к текущей серии и кнопка закрытия
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
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "График выхода серий",
                                color = CinemaTextWhite,
                                fontSize = 17.sp,
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Быстрая кнопка сброса к текущей доступной серии
                        if (selectedYear != initialTarget.year || selectedMonth != initialTarget.month) {
                            IconButton(
                                onClick = {
                                    selectedYear = initialTarget.year
                                    selectedMonth = initialTarget.month
                                    selectedDay = initialTarget.day
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(CinemaCard, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Today,
                                    contentDescription = "К текущей серии",
                                    tint = CinemaPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .background(CinemaCard, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = CinemaTextWhite,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Навигация по месяцам и годам
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CinemaCard, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
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
                            selectedDay = null
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Предыдущий месяц",
                            tint = CinemaTextWhite,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Text(
                        text = "${MONTH_NAMES_RU.getOrElse(selectedMonth - 1) { "" }} $selectedYear",
                        color = CinemaTextWhite,
                        fontSize = 15.sp,
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
                            selectedDay = null
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Следующий месяц",
                            tint = CinemaTextWhite,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Дни недели (Пн .. Вс)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    WEEKDAY_NAMES_RU.forEachIndexed { idx, dayName ->
                        val isWeekend = idx >= 5
                        Text(
                            text = dayName,
                            color = if (isWeekend) CinemaAmber.copy(alpha = 0.8f) else CinemaTextGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Календарная сетка месяца (6 недель макс)
                val totalCells = firstDayOfWeekIndex + daysInMonth
                val totalRows = (totalCells + 6) / 7

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CinemaSecondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                    val isSelected = selectedDay == dayNum

                                    val bgAnim by animateColorAsState(
                                        targetValue = when {
                                            isSelected -> CinemaPrimary
                                            hasEpisode && hasReleased -> Color(0xFF2E7D32).copy(alpha = 0.35f)
                                            hasEpisode -> CinemaAmber.copy(alpha = 0.25f)
                                            else -> Color.Transparent
                                        },
                                        label = "dayBg"
                                    )

                                    val borderColor = when {
                                        isSelected -> CinemaPrimary
                                        hasEpisode && hasReleased -> Color(0xFF4CAF50).copy(alpha = 0.8f)
                                        hasEpisode -> CinemaAmber.copy(alpha = 0.8f)
                                        else -> Color.Transparent
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1.1f)
                                            .padding(2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(bgAnim)
                                            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                            .clickable {
                                                selectedDay = if (selectedDay == dayNum) null else dayNum
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = dayNum.toString(),
                                                color = when {
                                                    isSelected -> CinemaTextWhite
                                                    hasEpisode -> CinemaTextWhite
                                                    else -> CinemaTextGray
                                                },
                                                fontSize = 12.sp,
                                                fontWeight = if (hasEpisode || isSelected) FontWeight.Bold else FontWeight.Normal
                                            )

                                            // Точка-индикатор выхода серии
                                            if (hasEpisode) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (isSelected) CinemaTextWhite
                                                            else if (hasReleased) Color(0xFF4CAF50)
                                                            else CinemaAmber
                                                        )
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Пустая ячейка для выравнивания
                                    Spacer(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1.1f)
                                            .padding(2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Легенда цветов
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFF4CAF50), CircleShape))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Вышла", color = CinemaTextWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)

                    Spacer(modifier = Modifier.width(18.dp))

                    Box(modifier = Modifier.size(8.dp).background(CinemaAmber, CircleShape))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Ожидается", color = CinemaTextWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = CinemaCard,
                    thickness = 0.8.dp
                )

                // Список серий: если выбран конкретный день — показываем серии этого дня, иначе все серии текущего месяца
                val displayedEpisodesList = remember(selectedDay, monthEpisodes, schedule) {
                    if (selectedDay != null) {
                        val dayEps = monthEpisodes.filter { it.day == selectedDay }
                        if (dayEps.isNotEmpty()) {
                            dayEps.map { it.item }
                        } else {
                            emptyList()
                        }
                    } else if (monthEpisodes.isNotEmpty()) {
                        monthEpisodes.map { it.item }
                    } else {
                        // Если в текущем месяце нет серий, показываем общее расписание
                        parsedEpisodes.map { it.item }
                    }
                }

                val listTitle = when {
                    selectedDay != null && displayedEpisodesList.isNotEmpty() ->
                        "Серии за $selectedDay ${MONTH_NAMES_RU.getOrElse(selectedMonth - 1) { "" }} $selectedYear"
                    selectedDay != null ->
                        "На $selectedDay число серий не запланировано"
                    monthEpisodes.isNotEmpty() ->
                        "Серии за ${MONTH_NAMES_RU.getOrElse(selectedMonth - 1) { "" }} $selectedYear (${monthEpisodes.size})"
                    else ->
                        "Все серии сериала (${schedule.size})"
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
                    items(
                        items = displayedEpisodesList,
                        key = { sch -> sch.seasonEpisode + "_" + sch.releaseDate + "_" + sch.ruReleaseDate }
                    ) { sch ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CinemaCard),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
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
                                    border = androidx.compose.foundation.BorderStroke(
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
                                                modifier = Modifier.size(14.dp)
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
                                                modifier = Modifier.size(14.dp)
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
    }
}

