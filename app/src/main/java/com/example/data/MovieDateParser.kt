package com.example.data

import java.util.Calendar

/**
 * Высокопроизводительный движок анализа дат и сортировки поисковой выдачи HDRezka.
 * Вычисляет целочисленный ключ даты YYYYMMDD за доли микросекунды с нулевым выделением памяти (Zero-Allocation),
 * обеспечивая минимальную нагрузку на процессор при любых объемах данных.
 */
object MovieDateParser {

    private val CURRENT_YEAR: Int = Calendar.getInstance().get(Calendar.YEAR)
    private const val MIN_VALID_YEAR = 1890
    private val MAX_VALID_YEAR = CURRENT_YEAR + 5

    /**
     * Компаратор для поисковой выдачи:
     * 1. Сортировка по дате выпуска: от самых новых к самым старым (descending).
     * 2. Вторичная сортировка при равенстве дат: по числовому ID новости DLE (более свежие релизы/добавления выше).
     */
    val MovieDateComparator: Comparator<RezkaItem> = Comparator { a, b ->
        val dateDiff = b.releaseDateNum.compareTo(a.releaseDateNum)
        if (dateDiff != 0) return@Comparator dateDiff
        b.numericId.compareTo(a.numericId)
    }

    /**
     * Быстрое извлечение 4-значного года из строки начиная с позиции [start] без создания объектов в Heap.
     */
    fun parse4Digits(str: CharSequence, start: Int): Int {
        if (start < 0 || start + 3 >= str.length) return -1
        val c0 = str[start]
        val c1 = str[start + 1]
        val c2 = str[start + 2]
        val c3 = str[start + 3]
        if (c0 !in '0'..'9' || c1 !in '0'..'9' || c2 !in '0'..'9' || c3 !in '0'..'9') return -1
        return (c0 - '0') * 1000 + (c1 - '0') * 100 + (c2 - '0') * 10 + (c3 - '0')
    }

    fun isValidYear(year: Int): Boolean {
        return year in MIN_VALID_YEAR..MAX_VALID_YEAR
    }

    /**
     * Извлечение целочисленной даты YYYYMMDD для фильма/сериала:
     * - При наличии точной даты (день.месяц.год): YYYY * 10000 + MM * 100 + DD (например 20241015)
     * - При наличии только года: YYYY * 10000 (например 20240000)
     * - Для сериалов с диапазоном (например, 2020-2024): выбирается наиболее свежий год (2024)
     * - Для онгоингов (например, 2022-...): актуальный текущий год
     * - При отсутствии даты: 0
     */
    fun extractReleaseDateNum(subtitle: String, url: String = ""): Int {
        if (subtitle.isNotEmpty()) {
            val fromSub = parseFromSubtitle(subtitle)
            if (fromSub > 0) return fromSub
        }
        if (url.isNotEmpty()) {
            val fromUrl = parseFromUrl(url)
            if (fromUrl > 0) return fromUrl
        }
        return 0
    }

    /**
     * Быстрый разбор строки подзаголовка карточки (год, диапазон лет, онгоинг или точная дата).
     */
    private fun parseFromSubtitle(subtitle: String): Int {
        val len = subtitle.length
        if (len == 0) return 0

        // 1. Проверка на точную числовую дату (например, 15.10.2024 или 2024-10-15)
        val exactDate = parseExactNumericDate(subtitle)
        if (exactDate > 0) return exactDate

        // 2. Однопроходный поиск 4-значных годов и диапазонов
        var firstYear = -1
        var secondYear = -1
        var hasHyphenAfterFirstYear = false
        var isOngoing = false

        var i = 0
        while (i < len) {
            val c = subtitle[i]
            if (c in '0'..'9') {
                val candidate = parse4Digits(subtitle, i)
                if (candidate != -1 && isValidYear(candidate)) {
                    if (firstYear == -1) {
                        firstYear = candidate
                        i += 4
                        // Проверяем, есть ли сразу после первого года дефис/тире
                        var j = i
                        while (j < len && (subtitle[j] == ' ' || subtitle[j] == '\t')) j++
                        if (j < len && (subtitle[j] == '-' || subtitle[j] == '–' || subtitle[j] == '—')) {
                            hasHyphenAfterFirstYear = true
                            j++
                            while (j < len && (subtitle[j] == ' ' || subtitle[j] == '\t')) j++
                            // Проверяем признаки онгоинга ("...", "…", "по наст", "наст", конец строки)
                            if (j >= len ||
                                subtitle.startsWith("...", j) ||
                                subtitle.startsWith("…", j) ||
                                subtitle.startsWith("по наст", j, ignoreCase = true) ||
                                subtitle.startsWith("наст", j, ignoreCase = true)
                            ) {
                                isOngoing = true
                            } else {
                                // Возможно, указан второй год диапазона (например, 2024)
                                val cand2 = parse4Digits(subtitle, j)
                                if (cand2 != -1 && isValidYear(cand2)) {
                                    secondYear = cand2
                                    i = j + 4
                                    break
                                }
                            }
                        }
                        continue
                    } else if (secondYear == -1) {
                        secondYear = candidate
                        break
                    }
                }
            } else if (firstYear != -1 && (c == '-' || c == '–' || c == '—')) {
                hasHyphenAfterFirstYear = true
                var j = i + 1
                while (j < len && (subtitle[j] == ' ' || subtitle[j] == '\t')) j++
                if (j >= len ||
                    subtitle.startsWith("...", j) ||
                    subtitle.startsWith("…", j) ||
                    subtitle.startsWith("по наст", j, ignoreCase = true) ||
                    subtitle.startsWith("наст", j, ignoreCase = true)
                ) {
                    isOngoing = true
                    break
                }
            }
            i++
        }

        // Если сериал продолжается прямо сейчас (онгоинг)
        if (isOngoing) {
            return CURRENT_YEAR * 10000
        }

        // Если указан диапазон лет (например, 2018 - 2023)
        if (firstYear != -1 && secondYear != -1) {
            val mostRecent = maxOf(firstYear, secondYear)
            return mostRecent * 10000
        }

        // Если указан один год
        if (firstYear != -1) {
            return firstYear * 10000
        }

        // 3. Запасной вариант: текстовая дата ("15 октября 2024") через ScheduleDateParser
        try {
            val parsed = ScheduleDateParser.parseDate(subtitle)
            if (parsed != null && isValidYear(parsed.year)) {
                return parsed.dateNum
            }
        } catch (_: Exception) {}

        return 0
    }

    /**
     * Быстрый разбор числовой даты вида DD.MM.YYYY или YYYY-MM-DD без регулярок.
     */
    private fun parseExactNumericDate(str: String): Int {
        val len = str.length
        if (len < 8) return 0

        // Проверяем формат DD.MM.YYYY (например, 15.10.2024)
        for (i in 0..(len - 10)) {
            val c0 = str[i]
            val c1 = str[i + 1]
            val sep1 = str[i + 2]
            val c3 = str[i + 3]
            val c4 = str[i + 4]
            val sep2 = str[i + 5]
            if ((sep1 == '.' || sep1 == '/') && (sep2 == '.' || sep2 == '/')) {
                if (c0 in '0'..'9' && c1 in '0'..'9' && c3 in '0'..'9' && c4 in '0'..'9') {
                    val day = (c0 - '0') * 10 + (c1 - '0')
                    val month = (c3 - '0') * 10 + (c4 - '0')
                    val year = parse4Digits(str, i + 6)
                    if (isValidYear(year) && month in 1..12 && day in 1..31) {
                        return year * 10000 + month * 100 + day
                    }
                }
            }
        }

        // Проверяем формат YYYY-MM-DD или YYYY.MM.DD
        for (i in 0..(len - 10)) {
            val year = parse4Digits(str, i)
            if (isValidYear(year)) {
                val sep1 = str[i + 4]
                val c5 = str[i + 5]
                val c6 = str[i + 6]
                val sep2 = str[i + 7]
                val c8 = str[i + 8]
                val c9 = str[i + 9]
                if ((sep1 == '-' || sep1 == '.') && (sep2 == '-' || sep2 == '.')) {
                    if (c5 in '0'..'9' && c6 in '0'..'9' && c8 in '0'..'9' && c9 in '0'..'9') {
                        val month = (c5 - '0') * 10 + (c6 - '0')
                        val day = (c8 - '0') * 10 + (c9 - '0')
                        if (month in 1..12 && day in 1..31) {
                            return year * 10000 + month * 100 + day
                        }
                    }
                }
            }
        }

        return 0
    }

    /**
     * Извлечение года из URL фильма (например ".../12345-title-2024.html").
     */
    private fun parseFromUrl(url: String): Int {
        val clean = url.substringBefore('?').substringBefore('#')
        val lastSlash = clean.lastIndexOf('/')
        val segment = if (lastSlash >= 0) clean.substring(lastSlash + 1) else clean
        val withoutExt = segment.substringBefore(".html")

        // Ищем 4-значный год с конца слага
        var i = withoutExt.length - 4
        while (i >= 0) {
            val year = parse4Digits(withoutExt, i)
            if (isValidYear(year)) {
                // Убеждаемся, что перед годом разделитель '-' или '_'
                if (i == 0 || withoutExt[i - 1] == '-' || withoutExt[i - 1] == '_') {
                    return year * 10000
                }
            }
            i--
        }
        return 0
    }

    /**
     * Быстрое извлечение числового ID новости DLE (числовой префикс в URL/ID).
     */
    fun extractNumericId(id: String, url: String = ""): Long {
        val source = if (id.isNotEmpty()) id else url
        if (source.isEmpty()) return 0L

        var i = 0
        val len = source.length
        val lastSlash = source.lastIndexOf('/')
        if (lastSlash >= 0 && lastSlash + 1 < len) {
            i = lastSlash + 1
        }

        while (i < len && !source[i].isDigit()) {
            i++
        }
        var num = 0L
        while (i < len && source[i].isDigit()) {
            val nextDigit = source[i] - '0'
            // Защита от переполнения Long
            if (num > (Long.MAX_VALUE - nextDigit) / 10) break
            num = num * 10 + nextDigit
            i++
        }
        return num
    }
}
