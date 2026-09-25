package com.example.data

import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Высокопроизводительный движок распознавания стран и сопоставления их с эмодзи-флагами.
 *
 * Архитектурные особенности:
 * 1. Полная поддержка всех 261 стран и территорий мира по стандарту Unicode/CLDR
 *    на русском, английском, национальном языках + разговорные сокращения и падежи.
 * 2. Алгоритмическая генерация Unicode Regional Indicator Symbols (0x1F1E6..) для всех стран.
 * 3. Поддержка субдивизий Великобритании (🏴󠁧󠁢󠁥󠁮󠁧󠁿 Англия, 🏴󠁧󠁢󠁳󠁣󠁴󠁿 Шотландия, 🏴󠁧󠁢󠁷󠁬󠁳󠁿 Уэльс).
 * 4. Fallback на единый эмодзи пиратского флага 🏴‍☠️ (U+1F3F4 U+200D U+2620 U+FE0F с ZWJ)
 *    строго для стран без официального эмодзи в стандарте Unicode (СССР, Югославия и т.п.).
 * 5. Полная поддержка Германии во всех формах: «Германия (ГДР)», «Германия (ФРГ)», «ГДР», «ФРГ» -> 🇩🇪.
 * 6. Полная поддержка Кореи («Корея Южная», «Корея», «Корея (Южная)» -> 🇰🇷, «Корея Северная» -> 🇰🇵).
 * 7. Полная идемпотентность: защита от повторного добавления флагов и очистка существующих флагов.
 * 8. LRU-кэш для минимальной нагрузки на CPU при быстром скролле.
 */
object CountryFlags {

    /**
     * Единый стандартный символ пиратского флага (Jolly Roger) в стандарте Unicode Emoji:
     * U+1F3F4 (Black Flag) + U+200D (Zero Width Joiner) + U+2620 (Skull and Crossbones) + U+FE0F (VS-16).
     * Отображается как ОДИН единый эмодзи-глиф.
     */
    const val FALLBACK_FLAG = "\uD83C\uDFF4\u200D\u2620\uFE0F"

    // Эмодзи субдивизий (ISO 3166-2 / Unicode TAG sequence)
    private const val FLAG_ENGLAND = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
    private const val FLAG_SCOTLAND = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
    private const val FLAG_WALES = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC77\uDB40\uDC6C\uDB40\uDC73\uDB40\uDC7F"

    private class FastLruMap<K, V>(private val maxCapacity: Int) : LinkedHashMap<K, V>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxCapacity
    }

    // Потокобезопасный LRU-кэш для мгновенного скролла
    private val formatCache: MutableMap<String, String> = Collections.synchronizedMap(FastLruMap(512))
    private val flagLookupCache: MutableMap<String, String> = Collections.synchronizedMap(FastLruMap(256))

    // Исторические и особые государства, у которых нет эмодзи-флага в Unicode
    private val COUNTRIES_WITHOUT_EMOJI = hashSetOf(
        "ссср", "ussr", "советский союз",
        "югославия", "yugoslavia",
        "чехословакия", "czechoslovakia",
        "российская империя", "russian empire",
        "османская империя", "ottoman empire",
        "римская империя", "древний рим",
        "заир", "сиам"
    )

    // Известные жанры кино для исключения ложных срабатываний
    private val KNOWN_GENRES = hashSetOf(
        "аниме", "биография", "биографии", "биографический",
        "боевик", "боевики", "вестерн", "вестерны",
        "военный", "военные", "детектив", "детективы",
        "детский", "детские", "для детей",
        "документальный", "документальные", "документалка",
        "драма", "драмы", "игра", "игры",
        "исторический", "исторические", "история",
        "комедия", "комедии", "короткометражка", "короткометражки", "короткометражный",
        "криминал", "мелодрама", "мелодрамы",
        "музыка", "музыкальный", "музыкальные", "мюзикл", "мюзиклы",
        "мультфильм", "мультфильмы", "мультипликация", "анимация",
        "приключения", "реалити-шоу", "реальное тв",
        "семейный", "семейные", "спорт", "спортивный", "спортивные",
        "ток-шоу", "триллер", "триллеры",
        "ужасы", "хоррор", "фантастика", "научная фантастика",
        "фэнтези", "фэнтэзи", "эротика", "хентай",
        "фильм-нуар", "нуар", "концерт", "церемония",
        "сериал", "сериалы", "фильм", "фильмы", "тв-шоу", "шоу",
        "новости", "передача", "стендап", "stand up", "скетч",
        "action", "comedy", "drama", "thriller", "horror", "sci-fi", "fantasy", "animation", "documentary"
    )

    // Основная статическая таблица сопоставления названий и кодов стран к эмодзи-флагам
    private val countryFlagMap: Map<String, String> by lazy {
        val map = HashMap<String, String>(2500)

        // 1. Автоматическое наполнение из всей базы ISO-3166 CLDR
        val ruLocale = Locale.forLanguageTag("ru")
        val enLocale = Locale.ENGLISH

        for (code in Locale.getISOCountries()) {
            val emoji = isoToEmoji(code)
            if (emoji.isEmpty()) continue

            val loc = Locale.Builder().setRegion(code).build()
            val ruName = loc.getDisplayCountry(ruLocale)
            val enName = loc.getDisplayCountry(enLocale)
            val nativeName = loc.getDisplayCountry(loc)

            putSafe(map, code, emoji)
            putSafe(map, ruName, emoji)
            putSafe(map, enName, emoji)
            putSafe(map, nativeName, emoji)

            try {
                val iso3 = loc.isO3Country
                if (iso3.isNotEmpty()) {
                    putSafe(map, iso3, emoji)
                }
            } catch (_: Exception) {
                // Игнорируем отсутствие ISO3 для редких кодов
            }
        }

        // 2. Явное внесение всех стран из эталонного списка
            putSafe(map, "о-в Вознесения", "🇦🇨")
            putSafe(map, "остров Вознесения", "🇦🇨")
            putSafe(map, "Андорра", "🇦🇩")
            putSafe(map, "ОАЭ", "🇦🇪")
            putSafe(map, "Афганистан", "🇦🇫")
            putSafe(map, "Антигуа и Барбуда", "🇦🇬")
            putSafe(map, "Ангилья", "🇦🇮")
            putSafe(map, "Албания", "🇦🇱")
            putSafe(map, "Армения", "🇦🇲")
            putSafe(map, "Ангола", "🇦🇴")
            putSafe(map, "Антарктида", "🇦🇶")
            putSafe(map, "Аргентина", "🇦🇷")
            putSafe(map, "Американское Самоа", "🇦🇸")
            putSafe(map, "Австрия", "🇦🇹")
            putSafe(map, "Австралия", "🇦🇺")
            putSafe(map, "Аруба", "🇦🇼")
            putSafe(map, "Аландские о-ва", "🇦🇽")
            putSafe(map, "Аландские острова", "🇦🇽")
            putSafe(map, "Азербайджан", "🇦🇿")
            putSafe(map, "Босния и Герцеговина", "🇧🇦")
            putSafe(map, "Барбадос", "🇧🇧")
            putSafe(map, "Бангладеш", "🇧🇩")
            putSafe(map, "Бельгия", "🇧🇪")
            putSafe(map, "Буркина-Фасо", "🇧🇫")
            putSafe(map, "Болгария", "🇧🇬")
            putSafe(map, "Бахрейн", "🇧🇭")
            putSafe(map, "Бурунди", "🇧🇮")
            putSafe(map, "Бенин", "🇧🇯")
            putSafe(map, "Сен-Бартелеми", "🇧🇱")
            putSafe(map, "Бермудские о-ва", "🇧🇲")
            putSafe(map, "Бермудские острова", "🇧🇲")
            putSafe(map, "Бруней", "🇧🇳")
            putSafe(map, "Боливия", "🇧🇴")
            putSafe(map, "Бонэйр, Синт-Эстатиус и Саба", "🇧🇶")
            putSafe(map, "Бразилия", "🇧🇷")
            putSafe(map, "Багамы", "🇧🇸")
            putSafe(map, "Бутан", "🇧🇹")
            putSafe(map, "о-в Буве", "🇧🇻")
            putSafe(map, "остров Буве", "🇧🇻")
            putSafe(map, "Ботсвана", "🇧🇼")
            putSafe(map, "Беларусь", "🇧🇾")
            putSafe(map, "Белиз", "🇧🇿")
            putSafe(map, "Канада", "🇨🇦")
            putSafe(map, "Кокосовые о-ва", "🇨🇨")
            putSafe(map, "Кокосовые острова", "🇨🇨")
            putSafe(map, "Конго - Киншаса", "🇨🇩")
            putSafe(map, "Центрально-Африканская Республика", "🇨🇫")
            putSafe(map, "Конго - Браззавиль", "🇨🇬")
            putSafe(map, "Швейцария", "🇨🇭")
            putSafe(map, "Кот-д’Ивуар", "🇨🇮")
            putSafe(map, "Острова Кука", "🇨🇰")
            putSafe(map, "Чили", "🇨🇱")
            putSafe(map, "Камерун", "🇨🇲")
            putSafe(map, "Китай", "🇨🇳")
            putSafe(map, "Колумбия", "🇨🇴")
            putSafe(map, "о-в Клиппертон", "🇨🇵")
            putSafe(map, "остров Клиппертон", "🇨🇵")
            putSafe(map, "Коста-Рика", "🇨🇷")
            putSafe(map, "Куба", "🇨🇺")
            putSafe(map, "Кабо-Верде", "🇨🇻")
            putSafe(map, "Кюрасао", "🇨🇼")
            putSafe(map, "о-в Рождества", "🇨🇽")
            putSafe(map, "остров Рождества", "🇨🇽")
            putSafe(map, "Кипр", "🇨🇾")
            putSafe(map, "Чехия", "🇨🇿")
            putSafe(map, "Германия", "🇩🇪")
            putSafe(map, "Диего-Гарсия", "🇩🇬")
            putSafe(map, "Джибути", "🇩🇯")
            putSafe(map, "Дания", "🇩🇰")
            putSafe(map, "Доминика", "🇩🇲")
            putSafe(map, "Доминиканская Республика", "🇩🇴")
            putSafe(map, "Алжир", "🇩🇿")
            putSafe(map, "Сеута и Мелилья", "🇪🇦")
            putSafe(map, "Эквадор", "🇪🇨")
            putSafe(map, "Эстония", "🇪🇪")
            putSafe(map, "Египет", "🇪🇬")
            putSafe(map, "Западная Сахара", "🇪🇭")
            putSafe(map, "Эритрея", "🇪🇷")
            putSafe(map, "Испания", "🇪🇸")
            putSafe(map, "Эфиопия", "🇪🇹")
            putSafe(map, "Европейский союз", "🇪🇺")
            putSafe(map, "Финляндия", "🇫🇮")
            putSafe(map, "Фиджи", "🇫🇯")
            putSafe(map, "Фолклендские о-ва", "🇫🇰")
            putSafe(map, "Фолклендские острова", "🇫🇰")
            putSafe(map, "Федеративные Штаты Микронезии", "🇫🇲")
            putSafe(map, "Фарерские о-ва", "🇫🇴")
            putSafe(map, "Фарерские острова", "🇫🇴")
            putSafe(map, "Франция", "🇫🇷")
            putSafe(map, "Габон", "🇬🇦")
            putSafe(map, "Великобритания", "🇬🇧")
            putSafe(map, "Гренада", "🇬🇩")
            putSafe(map, "Грузия", "🇬🇪")
            putSafe(map, "Французская Гвиана", "🇬🇫")
            putSafe(map, "Гернси", "🇬🇬")
            putSafe(map, "Гана", "🇬🇭")
            putSafe(map, "Гибралтар", "🇬🇮")
            putSafe(map, "Гренландия", "🇬🇱")
            putSafe(map, "Гамбия", "🇬🇲")
            putSafe(map, "Гвинея", "🇬🇳")
            putSafe(map, "Гваделупа", "🇬🇵")
            putSafe(map, "Экваториальная Гвинея", "🇬🇶")
            putSafe(map, "Греция", "🇬🇷")
            putSafe(map, "Южная Георгия и Южные Сандвичевы о-ва", "🇬🇸")
            putSafe(map, "Южная Георгия и Южные Сандвичевы острова", "🇬🇸")
            putSafe(map, "Гватемала", "🇬🇹")
            putSafe(map, "Гуам", "🇬🇺")
            putSafe(map, "Гвинея-Бисау", "🇬🇼")
            putSafe(map, "Гайана", "🇬🇾")
            putSafe(map, "Гонконг (САР)", "🇭🇰")
            putSafe(map, "Гонконг САР", "🇭🇰")
            putSafe(map, "о-ва Херд и Макдональд", "🇭🇲")
            putSafe(map, "острова Херд и Макдональд", "🇭🇲")
            putSafe(map, "Гондурас", "🇭🇳")
            putSafe(map, "Хорватия", "🇭🇷")
            putSafe(map, "Гаити", "🇭🇹")
            putSafe(map, "Венгрия", "🇭🇺")
            putSafe(map, "Канарские о-ва", "🇮🇨")
            putSafe(map, "Канарские острова", "🇮🇨")
            putSafe(map, "Индонезия", "🇮🇩")
            putSafe(map, "Ирландия", "🇮🇪")
            putSafe(map, "Израиль", "🇮🇱")
            putSafe(map, "о-в Мэн", "🇮🇲")
            putSafe(map, "остров Мэн", "🇮🇲")
            putSafe(map, "Индия", "🇮🇳")
            putSafe(map, "Британская территория в Индийском океане", "🇮🇴")
            putSafe(map, "Ирак", "🇮🇶")
            putSafe(map, "Иран", "🇮🇷")
            putSafe(map, "Исландия", "🇮🇸")
            putSafe(map, "Италия", "🇮🇹")
            putSafe(map, "Джерси", "🇯🇪")
            putSafe(map, "Ямайка", "🇯🇲")
            putSafe(map, "Иордания", "🇯🇴")
            putSafe(map, "Япония", "🇯🇵")
            putSafe(map, "Кения", "🇰🇪")
            putSafe(map, "Киргизия", "🇰🇬")
            putSafe(map, "Камбоджа", "🇰🇭")
            putSafe(map, "Кирибати", "🇰🇮")
            putSafe(map, "Коморы", "🇰🇲")
            putSafe(map, "Сент-Китс и Невис", "🇰🇳")
            putSafe(map, "КНДР", "🇰🇵")
            putSafe(map, "Республика Корея", "🇰🇷")
            putSafe(map, "Кувейт", "🇰🇼")
            putSafe(map, "Острова Кайман", "🇰🇾")
            putSafe(map, "Казахстан", "🇰🇿")
            putSafe(map, "Лаос", "🇱🇦")
            putSafe(map, "Ливан", "🇱🇧")
            putSafe(map, "Сент-Люсия", "🇱🇨")
            putSafe(map, "Лихтенштейн", "🇱🇮")
            putSafe(map, "Шри-Ланка", "🇱🇰")
            putSafe(map, "Либерия", "🇱🇷")
            putSafe(map, "Лесото", "🇱🇸")
            putSafe(map, "Литва", "🇱🇹")
            putSafe(map, "Люксембург", "🇱🇺")
            putSafe(map, "Латвия", "🇱🇻")
            putSafe(map, "Ливия", "🇱🇾")
            putSafe(map, "Марокко", "🇲🇦")
            putSafe(map, "Монако", "🇲🇨")
            putSafe(map, "Молдова", "🇲🇩")
            putSafe(map, "Черногория", "🇲🇪")
            putSafe(map, "Сен-Мартен", "🇲🇫")
            putSafe(map, "Мадагаскар", "🇲🇬")
            putSafe(map, "Маршалловы Острова", "🇲🇭")
            putSafe(map, "Северная Македония", "🇲🇰")
            putSafe(map, "Мали", "🇲🇱")
            putSafe(map, "Мьянма (Бирма)", "🇲🇲")
            putSafe(map, "Мьянма Бирма", "🇲🇲")
            putSafe(map, "Монголия", "🇲🇳")
            putSafe(map, "Макао (САР)", "🇲🇴")
            putSafe(map, "Макао САР", "🇲🇴")
            putSafe(map, "Северные Марианские о-ва", "🇲🇵")
            putSafe(map, "Северные Марианские острова", "🇲🇵")
            putSafe(map, "Мартиника", "🇲🇶")
            putSafe(map, "Мавритания", "🇲🇷")
            putSafe(map, "Монтсеррат", "🇲🇸")
            putSafe(map, "Мальта", "🇲🇹")
            putSafe(map, "Маврикий", "🇲🇺")
            putSafe(map, "Мальдивы", "🇲🇻")
            putSafe(map, "Малави", "🇲🇼")
            putSafe(map, "Мексика", "🇲🇽")
            putSafe(map, "Малайзия", "🇲🇾")
            putSafe(map, "Мозамбик", "🇲🇿")
            putSafe(map, "Намибия", "🇳🇦")
            putSafe(map, "Новая Каледония", "🇳🇨")
            putSafe(map, "Нигер", "🇳🇪")
            putSafe(map, "о-в Норфолк", "🇳🇫")
            putSafe(map, "остров Норфолк", "🇳🇫")
            putSafe(map, "Нигерия", "🇳🇬")
            putSafe(map, "Никарагуа", "🇳🇮")
            putSafe(map, "Нидерланды", "🇳🇱")
            putSafe(map, "Норвегия", "🇳🇴")
            putSafe(map, "Непал", "🇳🇵")
            putSafe(map, "Науру", "🇳🇷")
            putSafe(map, "Ниуэ", "🇳🇺")
            putSafe(map, "Новая Зеландия", "🇳🇿")
            putSafe(map, "Оман", "🇴🇲")
            putSafe(map, "Панама", "🇵🇦")
            putSafe(map, "Перу", "🇵🇪")
            putSafe(map, "Французская Полинезия", "🇵🇫")
            putSafe(map, "Папуа — Новая Гвинея", "🇵🇬")
            putSafe(map, "Филиппины", "🇵🇭")
            putSafe(map, "Пакистан", "🇵🇰")
            putSafe(map, "Польша", "🇵🇱")
            putSafe(map, "Сен-Пьер и Микелон", "🇵🇲")
            putSafe(map, "о-ва Питкэрн", "🇵🇳")
            putSafe(map, "острова Питкэрн", "🇵🇳")
            putSafe(map, "Пуэрто-Рико", "🇵🇷")
            putSafe(map, "Палестинские территории", "🇵🇸")
            putSafe(map, "Португалия", "🇵🇹")
            putSafe(map, "Палау", "🇵🇼")
            putSafe(map, "Парагвай", "🇵🇾")
            putSafe(map, "Катар", "🇶🇦")
            putSafe(map, "Реюньон", "🇷🇪")
            putSafe(map, "Румыния", "🇷🇴")
            putSafe(map, "Сербия", "🇷🇸")
            putSafe(map, "Россия", "🇷🇺")
            putSafe(map, "Руанда", "🇷🇼")
            putSafe(map, "Саудовская Аравия", "🇸🇦")
            putSafe(map, "Соломоновы Острова", "🇸🇧")
            putSafe(map, "Сейшельские Острова", "🇸🇨")
            putSafe(map, "Судан", "🇸🇩")
            putSafe(map, "Швеция", "🇸🇪")
            putSafe(map, "Сингапур", "🇸🇬")
            putSafe(map, "о-в Св. Елены", "🇸🇭")
            putSafe(map, "остров Св. Елены", "🇸🇭")
            putSafe(map, "Словения", "🇸🇮")
            putSafe(map, "Шпицберген и Ян-Майен", "🇸🇯")
            putSafe(map, "Словакия", "🇸🇰")
            putSafe(map, "Сьерра-Леоне", "🇸🇱")
            putSafe(map, "Сан-Марино", "🇸🇲")
            putSafe(map, "Сенегал", "������🇳")
            putSafe(map, "Сомали", "🇸🇴")
            putSafe(map, "Суринам", "🇸🇷")
            putSafe(map, "Южный Судан", "🇸🇸")
            putSafe(map, "Сан-Томе и Принсипи", "🇸🇹")
            putSafe(map, "Сальвадор", "🇸🇻")
            putSafe(map, "Синт-Мартен", "🇸🇽")
            putSafe(map, "Сирия", "🇸🇾")
            putSafe(map, "Эсватини", "🇸🇿")
            putSafe(map, "Тристан-да-Кунья", "🇹🇦")
            putSafe(map, "Тёркс и Кайкос", "🇹🇨")
            putSafe(map, "Чад", "🇹🇩")
            putSafe(map, "Французские Южные территории", "🇹🇫")
            putSafe(map, "Того", "🇹🇬")
            putSafe(map, "Таиланд", "🇹🇭")
            putSafe(map, "Таджикистан", "🇹🇯")
            putSafe(map, "Токелау", "🇹🇰")
            putSafe(map, "Восточный Тимор", "🇹🇱")
            putSafe(map, "Туркменистан", "🇹🇲")
            putSafe(map, "Тунис", "🇹🇳")
            putSafe(map, "Тонга", "🇹🇴")
            putSafe(map, "Турция", "🇹🇷")
            putSafe(map, "Тринидад и Тобаго", "🇹🇹")
            putSafe(map, "Тувалу", "🇹🇻")
            putSafe(map, "Тайвань", "🇹🇼")
            putSafe(map, "Танзания", "🇹🇿")
            putSafe(map, "Украина", "🇺🇦")
            putSafe(map, "Уганда", "🇺🇬")
            putSafe(map, "Внешние малые о-ва (США)", "🇺🇲")
            putSafe(map, "Внешние малые о-ва США", "🇺🇲")
            putSafe(map, "Внешние малые острова (США)", "🇺🇲")
            putSafe(map, "Организация Объединенных Наций", "🇺🇳")
            putSafe(map, "Соединенные Штаты", "🇺🇸")
            putSafe(map, "Уругвай", "🇺🇾")
            putSafe(map, "Узбекистан", "🇺🇿")
            putSafe(map, "Ватикан", "🇻🇦")
            putSafe(map, "Сент-Винсент и Гренадины", "🇻🇨")
            putSafe(map, "Венесуэла", "🇻🇪")
            putSafe(map, "Виргинские о-ва (Великобритания)", "🇻🇬")
            putSafe(map, "Виргинские о-ва Великобритания", "🇻🇬")
            putSafe(map, "Виргинские острова (Великобритания)", "🇻🇬")
            putSafe(map, "Виргинские о-ва (США)", "🇻🇮")
            putSafe(map, "Виргинские о-ва США", "🇻🇮")
            putSafe(map, "Виргинские острова (США)", "🇻🇮")
            putSafe(map, "Вьетнам", "🇻🇳")
            putSafe(map, "Вануату", "🇻🇺")
            putSafe(map, "Уоллис и Футуна", "🇼🇫")
            putSafe(map, "Самоа", "🇼🇸")
            putSafe(map, "Косово", "🇽🇰")
            putSafe(map, "Йемен", "🇾🇪")
            putSafe(map, "Майотта", "🇾🇹")
            putSafe(map, "Южно-Африканская Республика", "🇿🇦")
            putSafe(map, "Замбия", "🇿🇲")
            putSafe(map, "Зимбабве", "🇿🇼")
        // 3. Дополнительные и разговорные синонимы, исторические и сокращенные названия
        val customMappings = mapOf(
            // Казахстан и Средняя Азия
            "казахстан" to "🇰🇿",
            "казахстана" to "🇰🇿",
            "kazakhstan" to "🇰🇿",
            "рк" to "🇰🇿",
            "узбекистан" to "🇺🇿",
            "узбекистана" to "🇺🇿",
            "узбекская" to "🇺🇿",
            "кыргызстан" to "🇰🇬",
            "киргизия" to "🇰🇬",
            "киргизии" to "🇰🇬",
            "таджикистан" to "🇹🇯",
            "таджикистана" to "🇹🇯",
            "туркменистан" to "🇹🇲",
            "туркмения" to "🇹🇲",

            // США и Северная Америка
            "сша" to "🇺🇸",
            "usa" to "🇺🇸",
            "united states" to "🇺🇸",
            "америка" to "🇺🇸",
            "америки" to "🇺🇸",
            "соединенные штаты" to "🇺🇸",
            "соединенные штаты америки" to "🇺🇸",
            "канада" to "🇨🇦",
            "канады" to "🇨🇦",
            "мексика" to "🇲🇽",
            "мексики" to "🇲🇽",

            // Германия, ГДР, ФРГ во всех возможных форматах
            "германия" to "🇩🇪",
            "германии" to "🇩🇪",
            "германию" to "🇩🇪",
            "германия гдр" to "🇩🇪",
            "германии гдр" to "🇩🇪",
            "германия (гдр)" to "🇩🇪",
            "германии (гдр)" to "🇩🇪",
            "гдр" to "🇩🇪",
            "германия фрг" to "🇩🇪",
            "германии фрг" to "🇩🇪",
            "германия (фрг)" to "🇩🇪",
            "германии (фрг)" to "🇩🇪",
            "фрг" to "🇩🇪",
            "germany" to "🇩🇪",
            "gdr" to "🇩🇪",
            "frg" to "🇩🇪",

            // Корея (Южная и Северная во всех вариантах написания на Rezka)
            "корея" to "🇰🇷",
            "кореи" to "🇰🇷",
            "корею" to "🇰🇷",
            "корея южная" to "🇰🇷",
            "кореи южной" to "🇰🇷",
            "корею южную" to "🇰🇷",
            "южная корея" to "🇰🇷",
            "южной кореи" to "🇰🇷",
            "южную корею" to "🇰🇷",
            "республика корея" to "🇰🇷",
            "республики корея" to "🇰🇷",
            "корейская республика" to "🇰🇷",
            "юж корея" to "🇰🇷",
            "юж. корея" to "🇰🇷",
            "korea" to "🇰🇷",
            "south korea" to "🇰🇷",
            "republic of korea" to "🇰🇷",
            "корея северная" to "🇰🇵",
            "кореи северной" to "🇰🇵",
            "северная корея" to "🇰🇵",
            "северной кореи" to "🇰🇵",
            "кндр" to "🇰🇵",
            "north korea" to "🇰🇵",
            "dprk" to "🇰🇵",

            // Великобритания и части
            "великобритания" to "🇬🇧",
            "великобритании" to "🇬🇧",
            "united kingdom" to "🇬🇧",
            "uk" to "🇬🇧",
            "британия" to "🇬🇧",
            "соединенное королевство" to "🇬🇧",
            "англия" to FLAG_ENGLAND,
            "англии" to FLAG_ENGLAND,
            "england" to FLAG_ENGLAND,
            "шотландия" to FLAG_SCOTLAND,
            "шотландии" to FLAG_SCOTLAND,
            "scotland" to FLAG_SCOTLAND,
            "уэльс" to FLAG_WALES,
            "уэльса" to FLAG_WALES,
            "wales" to FLAG_WALES,
            "северная ирландия" to "🇬🇧",

            // Россия, Беларусь, Украина
            "россия" to "🇷🇺",
            "россии" to "🇷🇺",
            "рф" to "🇷🇺",
            "russia" to "🇷🇺",
            "беларусь" to "🇧🇾",
            "беларуси" to "🇧🇾",
            "белоруссия" to "🇧🇾",
            "белоруссии" to "🇧🇾",
            "украина" to "🇺🇦",
            "украины" to "🇺🇦",

            // Страны Закавказья
            "армения" to "🇦🇲",
            "армении" to "🇦🇲",
            "грузия" to "🇬🇪",
            "грузии" to "🇬🇪",
            "азербайджан" to "🇦🇿",
            "азербайджана" to "🇦🇿",

            // Восточная и Южная Азия
            "япония" to "🇯🇵",
            "японии" to "🇯🇵",
            "китай" to "🇨🇳",
            "китая" to "🇨🇳",
            "кнр" to "🇨🇳",
            "гонконг" to "🇭🇰",
            "гонконга" to "🇭🇰",
            "тайвань" to "🇹🇼",
            "тайваня" to "🇹🇼",
            "макао" to "🇲🇴",
            "индия" to "🇮🇳",
            "индии" to "🇮🇳",
            "пакистан" to "🇵🇰",
            "бангладеш" to "🇧🇩",
            "таиланд" to "🇹🇭",
            "таиланда" to "🇹🇭",
            "тайланд" to "🇹🇭",
            "тайланда" to "🇹🇭",
            "вьетнам" to "🇻🇳",
            "вьетнама" to "🇻🇳",
            "индонезия" to "🇮🇩",
            "индонезии" to "🇮🇩",
            "малайзия" to "🇲🇾",
            "малайзии" to "🇲🇾",
            "филиппины" to "🇵🇭",
            "филиппин" to "🇵🇭",
            "сингапур" to "🇸🇬",
            "сингапура" to "🇸🇬"
        )

        for ((name, flag) in customMappings) {
            putSafe(map, name, flag)
        }

        map
    }

    private fun putSafe(map: HashMap<String, String>, rawName: String, emoji: String) {
        val clean = rawName.trim().lowercase()
        if (clean.isNotEmpty()) {
            map[clean] = emoji
        }
    }

    /**
     * Удаляет из текста любые эмодзи флагов (включая Regional Indicators, 🏴, ☠️ и Unicode Tags).
     */
    fun stripFlags(text: String): String {
        val sb = java.lang.StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val charCount = Character.charCount(cp)
            when {
                // Regional Indicator Symbols U+1F1E6..U+1F1FF (флаги стран)
                cp in 0x1F1E6..0x1F1FF -> {}
                // Черный флаг, Череп с костями, знаки флагов, Variation Selector 16, Zero-width joiner
                cp == 0x1F3F4 || cp == 0x2620 || cp == 0x1F3C1 || cp == 0x1F6A9 || cp == 0xFE0F || cp == 0x200D -> {}
                // Unicode Tags для субдивизий
                cp in 0xE0000..0xE007F -> {}
                else -> sb.appendCodePoint(cp)
            }
            i += charCount
        }
        return sb.toString().trim()
    }

    /**
     * Проверяет, содержит ли строка уже какой-либо эмодзи флага.
     */
    fun hasFlag(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (cp in 0x1F1E6..0x1F1FF || cp == 0x1F3F4 || cp == 0x1F3C1 || cp == 0x1F6A9 || cp == 0x2620) {
                return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * Преобразует двухбуквенный ISO 3166-1 alpha-2 код в эмодзи флага.
     */
    fun isoToEmoji(code: String): String {
        if (code.length != 2) return ""
        val c1 = code[0].uppercaseChar()
        val c2 = code[1].uppercaseChar()
        if (c1 !in 'A'..'Z' || c2 !in 'A'..'Z') return ""

        val cp1 = 0x1F1E6 + (c1 - 'A')
        val cp2 = 0x1F1E6 + (c2 - 'A')

        val chars = Character.toChars(cp1) + Character.toChars(cp2)
        return String(chars)
    }

    /**
     * Возвращает флаг страны по её названию.
     * Если флаг не найден:
     * - при fallbackToDefault = true возвращается единый пиратский флаг 🏴‍☠️.
     * - при fallbackToDefault = false возвращается пустая строка.
     */
    fun getFlag(countryName: String, fallbackToDefault: Boolean = false): String {
        val pureName = stripFlags(countryName)
        val clean = cleanCountryName(pureName)
        if (clean.isEmpty()) return if (fallbackToDefault) FALLBACK_FLAG else ""

        // Проверяем исторические страны без официального Unicode эмодзи
        if (COUNTRIES_WITHOUT_EMOJI.contains(clean)) {
            return FALLBACK_FLAG
        }

        // Проверяем кэш быстрого поиска
        val cached = flagLookupCache[clean]
        if (cached != null) {
            return if (cached.isNotEmpty()) cached else (if (fallbackToDefault) FALLBACK_FLAG else "")
        }

        // 1. Прямой поиск в карте O(1)
        val directMatch = countryFlagMap[clean]
        if (directMatch != null) {
            flagLookupCache[clean] = directMatch
            return directMatch
        }

        // 2. Нормализация окончаний русского языка (родительный падеж)
        val stemmed = normalizeRussianStem(clean)
        val stemMatch = countryFlagMap[stemmed]
        if (stemMatch != null) {
            flagLookupCache[clean] = stemMatch
            return stemMatch
        }

        // 3. Поиск по составным словам (например, "южная корея", "новая зеландия", "корея южная", "германия гдр")
        if (clean.contains(' ')) {
            val words = clean.split(' ').map { normalizeRussianStem(it) }
            val joined = words.joinToString(" ")
            val multiWordMatch = countryFlagMap[joined]
            if (multiWordMatch != null) {
                flagLookupCache[clean] = multiWordMatch
                return multiWordMatch
            }
        }

        // Запоминаем промах в кэше
        flagLookupCache[clean] = ""
        return if (fallbackToDefault) FALLBACK_FLAG else ""
    }

    /**
     * Специализированное форматирование строки, содержащей исключительно страны
     * (например, поле "Страна" на странице фильма: "Казахстан, США" или "СССР").
     * Для любой страны гарантированно возвращается её флаг, а если эмодзи в Unicode нет — 🏴‍☠️.
     */
    fun formatCountries(countriesRaw: String): String {
        if (countriesRaw.isBlank()) return ""
        val parts = countriesRaw.split(",", "/", ";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return countriesRaw

        return parts.joinToString(", ") { part ->
            val pureName = stripFlags(part).trim()
            if (pureName.isEmpty()) {
                part
            } else {
                val flag = getFlag(pureName, fallbackToDefault = true)
                "$flag $pureName"
            }
        }
    }

    /**
     * Форматирует смешанную подпись каталога (например, "2024, Казахстан, Комедии" или "США, Боевики").
     * Не добавляет флаги перед годами и жанрами, но снабжает страны их флагами (или 🏴‍☠️ при их отсутствии).
     * Обладает полной идемпотентностью — повторный запуск не дублирует флаги.
     */
    fun formatWithFlags(rawSubtitle: String): String {
        if (rawSubtitle.isBlank()) return ""

        val cached = formatCache[rawSubtitle]
        if (cached != null) return cached

        val parts = rawSubtitle.split(",", "/", ";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return rawSubtitle

        val result = parts.joinToString(", ") { part ->
            when {
                // Если это год (2024, 2020-2023) - не трогаем
                isYearToken(part) -> part

                // Если это возрастной ценз (18+, PG-13) - не трогаем
                isAgeToken(part) -> part

                // Если это жанр кино (комедии, боевики, драмы) - не трогаем
                isGenreToken(part) -> part

                // Иначе проверяем, страна ли это
                else -> {
                    val pureName = stripFlags(part).trim()
                    if (pureName.isEmpty()) {
                        part
                    } else {
                        val flag = getFlag(pureName, fallbackToDefault = false)
                        if (flag.isNotEmpty()) {
                            "$flag $pureName"
                        } else if (COUNTRIES_WITHOUT_EMOJI.contains(cleanCountryName(pureName))) {
                            "$FALLBACK_FLAG $pureName"
                        } else if (hasFlag(part)) {
                            // Если в токене уже был какой-то флаг, сохраняем его корректно
                            part
                        } else {
                            part
                        }
                    }
                }
            }
        }

        formatCache[rawSubtitle] = result
        return result
    }

    private fun cleanCountryName(name: String): String {
        var s = name.trim().lowercase()
        // Очищаем скобки (например "Корея (Южная)" -> "корея южная", "Германия (ГДР)" -> "германия гдр")
        s = s.replace("(", " ").replace(")", " ").replace("[", " ").replace("]", " ")
        // Очищаем от кавычек
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.removeSurrounding("\"")
        if (s.startsWith("«") && s.endsWith("»")) s = s.removeSurrounding("«", "»")
        // Сжимаем множественные пробелы
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.endsWith(".")) s = s.dropLast(1).trim()
        return s
    }

    private fun normalizeRussianStem(name: String): String {
        val s = name.trim().lowercase()
        return when {
            s.endsWith("стана") -> s.dropLast(1) // казахстана -> казахстан
            s.endsWith("ии") -> s.dropLast(2) + "ия" // франции -> франция, германии -> германия, великобритании -> великобритания
            s.endsWith("ы") && s.length > 3 -> s.dropLast(1) + "а" // канады -> канада, польши -> польша, украины -> украина
            s.endsWith("еи") -> s.dropLast(2) + "ея" // кореи -> корея
            s.endsWith("ов") && s.length > 4 -> s.dropLast(2) + "ы" // нидерландов -> нидерланды
            s.endsWith("ев") && s.length > 4 -> s.dropLast(2) + "ы"
            s.endsWith("а") && s.length > 4 && s[s.length - 2] !in "аеёиоуыэюя" -> s.dropLast(1) // китая -> китай, египта -> египет, вьетнама -> вьетнам
            s.endsWith("я") && s.length > 4 && s[s.length - 2] !in "аеёиоуыэюя" -> s.dropLast(1) + "ь" // израиля -> израиль
            else -> s
        }
    }

    private fun isYearToken(text: String): Boolean {
        val clean = stripFlags(text).trim()
        if (clean.length == 4 && clean.all { it.isDigit() }) return true
        if (clean.length in 5..10 && clean.contains('-')) {
            val parts = clean.split('-')
            if (parts.any { p -> p.length == 4 && p.all { it.isDigit() } }) return true
        }
        return false
    }

    private fun isAgeToken(text: String): Boolean {
        val clean = stripFlags(text).trim().uppercase()
        return clean.endsWith("+") || clean == "R" || clean == "PG" || clean == "PG-13" || clean == "NC-17" || clean == "G"
    }

    private fun isGenreToken(text: String): Boolean {
        val clean = stripFlags(text).trim().lowercase().removeSuffix(".").removeSuffix(",")
        return KNOWN_GENRES.contains(clean)
    }
}
