package com.example.data

import java.util.Calendar
import java.util.Locale

/**
 * Распарсенная дата серии расписания с привязкой к дню/месяцу/году
 * и предрассчитанным числовым значением YYYYMMDD для мгновенного сравнения дат за 1 такт CPU.
 */
data class ParsedScheduleDate(
    val day: Int,
    val month: Int, // 1..12
    val year: Int,
    val isReleased: Boolean
) {
    val dateNum: Int get() = year * 10000 + month * 100 + day
}

/**
 * Высокопроизводительный движок парсинга дат расписания серий Rezka
 * Поддерживает форматы:
 * - Текстовые: "15 октября 2024", "15 Oct 2024", "15 октября", "15 окт 2024 г."
 * - Числовые: "15.10.2024", "15/10/2024", "15.10.24", "15.10"
 * - ISO: "2024-10-15", "2024.10.15"
 * - Относительные: "Сегодня", "Вчера", "Завтра"
 */
object ScheduleDateParser {

    private val YEAR_REGEX = Regex("""\b(19\d\d|20\d\d)\b""")
    private val DAY_REGEX = Regex("""\b([1-9]|[12]\d|3[01])\b""")
    private val DOT_DATE_REGEX = Regex("""(\d{1,2})[./-](\d{1,2})(?:[./-](\d{2,4}))?""")
    private val ISO_DATE_REGEX = Regex("""(\d{4})[./-](\d{1,2})[./-](\d{1,2})""")

    /**
     * Возвращает сегодняшнюю дату в виде целого числа YYYYMMDD (например, 20260913)
     * Позволяет выполнять сравнение дат за 1 процессорную инструкцию без создания объектов.
     */
    fun getTodayDateNum(): Int {
        val cal = Calendar.getInstance()
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return y * 10000 + m * 100 + d
    }

    /**
     * Быстрый парсер строки даты в структуру [ParsedScheduleDate]
     */
    fun parseDate(
        dateStr: String,
        fallbackYear: Int = Calendar.getInstance().get(Calendar.YEAR),
        todayDateNum: Int = getTodayDateNum()
    ): ParsedScheduleDate? {
        val clean = dateStr.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return null

        val cal = Calendar.getInstance()
        val currentYear = cal.get(Calendar.YEAR)
        val currentMonth = cal.get(Calendar.MONTH) + 1
        val currentDay = cal.get(Calendar.DAY_OF_MONTH)

        // 1. Относительные даты
        if (clean.contains("сегодня") || clean.contains("today")) {
            return ParsedScheduleDate(currentDay, currentMonth, currentYear, isReleased = true)
        }
        if (clean.contains("вчера") || clean.contains("yesterday")) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return ParsedScheduleDate(d, m, y, isReleased = true)
        }
        if (clean.contains("завтра") || clean.contains("tomorrow")) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val dateNum = y * 10000 + m * 100 + d
            return ParsedScheduleDate(d, m, y, isReleased = dateNum <= todayDateNum)
        }

        // 2. Определение года (4 цифры)
        val yearMatch = YEAR_REGEX.find(clean)
        val year = yearMatch?.value?.toIntOrNull() ?: fallbackYear

        // 3. Определение месяца по названию (RU / EN)
        var month = 0
        when {
            clean.contains("янв") || clean.contains("jan") -> month = 1
            clean.contains("фев") || clean.contains("feb") -> month = 2
            clean.contains("мар") || clean.contains("mar") -> month = 3
            clean.contains("апр") || clean.contains("apr") -> month = 4
            clean.contains("ма[яе]".toRegex()) || clean.contains("май") || clean.contains("may") -> month = 5
            clean.contains("июн") || clean.contains("jun") -> month = 6
            clean.contains("июл") || clean.contains("jul") -> month = 7
            clean.contains("авг") || clean.contains("aug") -> month = 8
            clean.contains("сен") || clean.contains("sep") -> month = 9
            clean.contains("окт") || clean.contains("oct") -> month = 10
            clean.contains("ноя") || clean.contains("nov") -> month = 11
            clean.contains("дек") || clean.contains("dec") -> month = 12
        }

        if (month in 1..12) {
            val dayMatch = DAY_REGEX.find(clean)
            val day = dayMatch?.value?.toIntOrNull()
            if (day != null && day in 1..31) {
                val dateNum = year * 10000 + month * 100 + day
                return ParsedScheduleDate(day, month, year, isReleased = dateNum <= todayDateNum)
            }
        }

        // 4. Форматы ISO: ГГГГ-ММ-ДД или ГГГГ.ММ.ДД
        val isoMatch = ISO_DATE_REGEX.find(clean)
        if (isoMatch != null) {
            val y = isoMatch.groupValues[1].toIntOrNull() ?: fallbackYear
            val m = isoMatch.groupValues[2].toIntOrNull() ?: 1
            val d = isoMatch.groupValues[3].toIntOrNull() ?: 1
            if (d in 1..31 && m in 1..12) {
                val dateNum = y * 10000 + m * 100 + d
                return ParsedScheduleDate(d, m, y, isReleased = dateNum <= todayDateNum)
            }
        }

        // 5. Форматы ДД.ММ.ГГГГ или ДД.ММ
        val dotMatch = DOT_DATE_REGEX.find(clean)
        if (dotMatch != null) {
            val d = dotMatch.groupValues[1].toIntOrNull() ?: 1
            val m = dotMatch.groupValues[2].toIntOrNull() ?: 1
            val rawY = dotMatch.groupValues.getOrNull(3)
            var y = if (!rawY.isNullOrEmpty()) rawY.toIntOrNull() ?: fallbackYear else fallbackYear
            if (y < 100) y += 2000
            if (d in 1..31 && m in 1..12) {
                val dateNum = y * 10000 + m * 100 + d
                return ParsedScheduleDate(d, m, y, isReleased = dateNum <= todayDateNum)
            }
        }

        return null
    }

    /**
     * Сравнивает даты серии (рус и ориг) с текущей датой и определяет, вышла ли серия
     */
    fun isEpisodeReleased(
        releaseDate: String,
        ruReleaseDate: String,
        fallbackYear: Int,
        hasHtmlReleasedFlag: Boolean = false,
        todayDateNum: Int = getTodayDateNum()
    ): Boolean {
        if (hasHtmlReleasedFlag) return true

        val parsedRu = if (ruReleaseDate.isNotEmpty()) parseDate(ruReleaseDate, fallbackYear, todayDateNum) else null
        val parsedOrig = if (releaseDate.isNotEmpty()) parseDate(releaseDate, fallbackYear, todayDateNum) else null

        if (parsedRu != null) {
            return parsedRu.isReleased
        }
        if (parsedOrig != null) {
            return parsedOrig.isReleased
        }

        // Если точную дату не удалось распарсить, но указан старый год сериала
        val currentYear = todayDateNum / 10000
        if (fallbackYear in 1 until currentYear) {
            return true
        }

        return false
    }
}
