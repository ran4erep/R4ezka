package com.example

import com.example.data.AnubisInterceptor
import com.example.data.RezkaDecryptor
import com.example.data.WatchHistoryEntity
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun anubis_powSolver_solvesCorrectly() {
        val testData = "8cefe6214e7af263705c827b3b5cd012f94bd379"
        val difficulty = 2
        val (nonce, hash) = AnubisInterceptor.solvePow(testData, difficulty)

        assertTrue("Nonce should be non-negative", nonce >= 0)
        assertTrue("Hash should start with $difficulty zeroes", hash.startsWith("00"))
    }

    @Test
    fun rezkaDecryptor_parsesAllQualitiesCorrectly_commaSeparated() {
        val sampleComma = "[360p]https://stream.rezka.ag/hls/1/360.mp4:hls:manifest.m3u8,[480p]https://stream.rezka.ag/hls/1/480.mp4:hls:manifest.m3u8,[720p]https://stream.rezka.ag/hls/1/720.mp4:hls:manifest.m3u8,[1080p]https://stream.rezka.ag/hls/1/1080.mp4:hls:manifest.m3u8,[1080p Ultra]https://stream.rezka.ag/hls/1/1080u.mp4:hls:manifest.m3u8"
        val streams = RezkaDecryptor.parseStreams(sampleComma)

        assertEquals("Should parse all 5 qualities", 5, streams.size)
        assertEquals("First stream should be highest quality (1080p Ultra)", "1080p Ultra", streams[0].quality)
        assertEquals("Second stream should be 1080p", "1080p", streams[1].quality)
        assertEquals("Third stream should be 720p", "720p", streams[2].quality)
        assertEquals("Fourth stream should be 480p", "480p", streams[3].quality)
        assertEquals("Fifth stream should be 360p", "360p", streams[4].quality)
        assertEquals("Direct MP4 URL should be extracted", "https://stream.rezka.ag/hls/1/1080.mp4", streams[1].directMp4Url)
    }

    @Test
    fun rezkaDecryptor_parsesAllQualitiesCorrectly_orSeparatedWithMirrors() {
        val sampleOr = "[360p]https://stream1/360.mp4:hls:manifest.m3u8,https://mirror1/360.mp4:hls:manifest.m3u8 or [720p]https://stream1/720.mp4:hls:manifest.m3u8 or [1080p]https://stream1/1080.mp4:hls:manifest.m3u8,https://mirror1/1080.mp4:hls:manifest.m3u8"
        val streams = RezkaDecryptor.parseStreams(sampleOr)

        assertEquals("Should parse 3 qualities", 3, streams.size)
        assertEquals("First stream should be 1080p", "1080p", streams[0].quality)
        assertEquals("1080p should have 1 backup mirror URL", 1, streams[0].backupUrls.size)
        assertEquals("Second stream should be 720p", "720p", streams[1].quality)
        assertEquals("Third stream should be 360p", "360p", streams[2].quality)
    }

    @Test
    fun scheduleDateParser_correctlyIdentifiesPastAndFutureEpisodes() {
        val todayNum = 20260913 // 13 сентября 2026

        // Прошедшие серии (должны быть isReleased = true)
        val pastDate1 = com.example.data.ScheduleDateParser.parseDate("15 мая 2024", 2024, todayNum)
        assertNotNull(pastDate1)
        assertEquals(15, pastDate1!!.day)
        assertEquals(5, pastDate1.month)
        assertEquals(2024, pastDate1.year)
        assertTrue("Серия 2024 года должна считаться вышедшей", pastDate1.isReleased)

        val pastDate2 = com.example.data.ScheduleDateParser.parseDate("10.09.2026", 2026, todayNum)
        assertNotNull(pastDate2)
        assertTrue("Серия от 10 сентября 2026 должна считаться вышедшей", pastDate2!!.isReleased)

        val todayDate = com.example.data.ScheduleDateParser.parseDate("13 сентября 2026", 2026, todayNum)
        assertNotNull(todayDate)
        assertTrue("Серия сегодняшнего дня должна считаться вышедшей", todayDate!!.isReleased)

        // Будущие серии (должны быть isReleased = false)
        val futureDate1 = com.example.data.ScheduleDateParser.parseDate("20 октября 2026", 2026, todayNum)
        assertNotNull(futureDate1)
        assertEquals(20, futureDate1!!.day)
        assertEquals(10, futureDate1.month)
        assertEquals(2026, futureDate1.year)
        assertFalse("Серия 20 октября 2026 должна считаться ожидаемой", futureDate1.isReleased)

        val futureDate2 = com.example.data.ScheduleDateParser.parseDate("15.01.2027", 2027, todayNum)
        assertNotNull(futureDate2)
        assertFalse("Серия 2027 года должна считаться ожидаемой", futureDate2!!.isReleased)

        // Проверка через isEpisodeReleased для старых сериалов
        val isReleasedOld = com.example.data.ScheduleDateParser.isEpisodeReleased(
            releaseDate = "12 мая 2019",
            ruReleaseDate = "13 мая 2019",
            fallbackYear = 2019,
            todayDateNum = todayNum
        )
        assertTrue("Серия 2019 года должна быть помечена как вышедшая", isReleasedOld)
    }

    @Test
    fun rezkaService_isNoResultsPage_detectsEmptySearchCorrectly() {
        val emptyHtmlSample = """
            <div class="b-content__main">
                <div class="b-search__message">Нам не удалось ничего найти. Может стоит изменить поисковый запрос?</div>
                <div class="b-sidebar">
                    <div class="b-content__inline_item">
                        <a href="/films/telegram.html">Telegram</a>
                    </div>
                </div>
            </div>
        """.trimIndent()

        assertTrue(
            "Должно обнаруживать ответ об отсутствии результатов",
            com.example.data.RezkaService.isNoResultsPage(emptyHtmlSample)
        )
    }

    @Test
    fun seriesProgress_userCase_season4Episode20Of5Seasons20Episodes_shows80PercentNot3Percent() {
        // Пользовательский сценарий: сериал 5 сезонов, по 20 серий в каждом (всего 100 серий).
        // Пользователь включил 4 сезон 20 серию (сквозной индекс 80).
        val historyEntry = com.example.data.WatchHistoryEntity(
            id = "test_123_4_20",
            itemId = "test_123",
            title = "Тестовый сериал",
            imageUrl = "",
            subtitle = "Сезон 4, Серия 20",
            season = 4,
            episode = "20",
            progressMs = 2700_000L, // 45 мин из 45 мин (досмотрена)
            durationMs = 2700_000L,
            totalEpisodes = 100,
            episodeIndex = 80,
            totalSeasons = 5
        )

        val result = com.example.ui.RezkaViewModel.calculateSeriesProgress(
            latest = historyEntry,
            items = listOf(historyEntry)
        )

        assertEquals("Сквозной номер серии должен быть 80", 80, result.absoluteEpisodeIndex)
        assertEquals("Всего серий должно быть 100", 100, result.totalEpisodesCount)
        assertEquals("Просмотрено должно быть 80 серий", 80, result.watchedEpisodesCount)
        assertEquals("Прогресс должен быть 80% (0.80f), а не 3%!", 0.80f, result.totalProgressFraction, 0.01f)
    }

    @Test
    fun seriesProgress_fallbackWithoutEpisodeIndex_calculatesCorrectProgress() {
        // Проверка случая старых записей, где episodeIndex еще не был записан
        val oldHistoryEntry = com.example.data.WatchHistoryEntity(
            id = "test_456_4_20",
            itemId = "test_456",
            title = "Сериал со старой историей",
            imageUrl = "",
            subtitle = "Сезон 4, Серия 20",
            season = 4,
            episode = "20",
            progressMs = 1350_000L, // 50% серии
            durationMs = 2700_000L,
            totalEpisodes = 100,
            episodeIndex = 0, // Не сохранен
            totalSeasons = 5
        )

        val result = com.example.ui.RezkaViewModel.calculateSeriesProgress(
            latest = oldHistoryEntry,
            items = listOf(oldHistoryEntry)
        )

        assertEquals("Сквозной индекс должен быть вычислен как 80", 80, result.absoluteEpisodeIndex)
        assertEquals("Всего серий 100", 100, result.totalEpisodesCount)
        assertEquals("Просмотрено серий до текущей 79", 79, result.watchedEpisodesCount)
        // 79 полных серий + 0.5 текущей = 79.5 / 100 = 79.5%
        assertEquals("Прогресс должен быть около 79.5%", 0.795f, result.totalProgressFraction, 0.01f)
    }

    @Test
    fun seriesProgress_firstEpisodePartiallyWatched() {
        // Сезон 1, серия 1 из 10 серий, посмотрено 50%
        val firstEp = com.example.data.WatchHistoryEntity(
            id = "test_789_1_1",
            itemId = "test_789",
            title = "Новый сериал",
            imageUrl = "",
            subtitle = "Сезон 1, Серия 1",
            season = 1,
            episode = "1",
            progressMs = 1500_000L,
            durationMs = 3000_000L, // 50%
            totalEpisodes = 10,
            episodeIndex = 1,
            totalSeasons = 1
        )

        val result = com.example.ui.RezkaViewModel.calculateSeriesProgress(
            latest = firstEp,
            items = listOf(firstEp)
        )

        assertEquals("Сквозной индекс 1", 1, result.absoluteEpisodeIndex)
        assertEquals("Всего серий 10", 10, result.totalEpisodesCount)
        assertEquals("Пока 0 полных серий просмотрено", 0, result.watchedEpisodesCount)
        assertEquals("Прогресс должен быть 5% (половина первой из 10 серий)", 0.05f, result.totalProgressFraction, 0.005f)
    }

    @Test
    fun seriesProgress_allEpisodesWatched_shows100PercentNot99Percent() {
        val lastEp = WatchHistoryEntity(
            id = "1",
            itemId = "test_1",
            title = "Тестовый сериал",
            imageUrl = "",
            subtitle = "10 серия",
            url = "",
            translatorId = "1",
            translatorName = "Дубляж",
            season = 1,
            episode = "10",
            progressMs = 2370_000L,
            durationMs = 2400_000L, // 98.75%
            totalEpisodes = 10,
            episodeIndex = 10,
            totalSeasons = 1
        )

        val result = com.example.ui.RezkaViewModel.calculateSeriesProgress(
            latest = lastEp,
            items = listOf(lastEp)
        )

        assertEquals("Сквозной индекс 10", 10, result.absoluteEpisodeIndex)
        assertEquals("Всего серий 10", 10, result.totalEpisodesCount)
        assertEquals("Все 10 серий просмотрены", 10, result.watchedEpisodesCount)
        assertEquals("Прогресс должен быть ровно 100% (1.0f), а не 99%", 1.0f, result.totalProgressFraction, 0.001f)
    }

    @Test
    fun parseSeasonsFromDoc_handlesCombinedEpisodesAndDifferentEpisodeCounts() {
        val html = """
            <div id="simple-seasons-tabs">
                <li class="b-simple_season__item" data-tab_id="1">Сезон 1</li>
                <li class="b-simple_season__item" data-tab_id="2">Сезон 2</li>
            </div>
            <ul class="b-simple_episodes__list" data-season_id="1">
                <li class="b-simple_episode__item" data-episode_id="1-2">Серия 1-2</li>
                <li class="b-simple_episode__item" data-episode_id="3">Серия 3</li>
            </ul>
            <ul class="b-simple_episodes__list" data-season_id="2">
                <li class="b-simple_episode__item" data-episode_id="1">Серия 1</li>
                <li class="b-simple_episode__item" data-episode_id="2">Серия 2</li>
                <li class="b-simple_episode__item" data-episode_id="3">Серия 3</li>
            </ul>
        """.trimIndent()

        val doc = org.jsoup.Jsoup.parse(html)
        val seasons = com.example.data.RezkaService.parseSeasonsFromDoc(doc, "56")

        assertEquals("Должно быть 2 сезона", 2, seasons.size)
        assertEquals("В первом сезоне 2 элемента (1-2 и 3)", 2, seasons[0].episodes.size)
        assertEquals("ID первой серии должен быть 1-2", "1-2", seasons[0].episodes[0].id)
        assertEquals("Название первой серии", "Серия 1-2", seasons[0].episodes[0].name)
        assertEquals("Во втором сезоне 3 серии", 3, seasons[1].episodes.size)
    }

    @Test
    fun movieDateParser_extractsReleaseDateNum_correctlyForAllFormats() {
        // Обычный фильм с одним годом
        val film1 = com.example.data.MovieDateParser.extractReleaseDateNum("2024, США, Боевик")
        assertEquals(20240000, film1)

        // Фильм с эмодзи-флагами после CountryFlags
        val filmFlags = com.example.data.MovieDateParser.extractReleaseDateNum("2025, 🇺🇸 США, 🇫🇷 Франция, Комедия")
        assertEquals(20250000, filmFlags)

        // Сериал с диапазоном годов (должен выбираться последний завершившийся год)
        val seriesFinished = com.example.data.MovieDateParser.extractReleaseDateNum("2018 - 2023, США, Драма")
        assertEquals(20230000, seriesFinished)

        // Сериал онгоинг ("2022 - ...")
        val ongoing1 = com.example.data.MovieDateParser.extractReleaseDateNum("2022 - ..., Япония, Аниме")
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        assertEquals(currentYear * 10000, ongoing1)

        // Сериал онгоинг ("2023 - по наст. время")
        val ongoing2 = com.example.data.MovieDateParser.extractReleaseDateNum("2023 - по наст. время, Южная Корея")
        assertEquals(currentYear * 10000, ongoing2)

        // Точная числовая дата
        val exactDate = com.example.data.MovieDateParser.extractReleaseDateNum("15.10.2024, Премьера")
        assertEquals(20241015, exactDate)

        // Год из URL при пустом подзаголовке
        val fromUrl = com.example.data.MovieDateParser.extractReleaseDateNum("", "https://hdrezka.ag/films/action/68900-gladiator-ii-2024.html")
        assertEquals(20240000, fromUrl)

        // Старый фильм
        val classicFilm = com.example.data.MovieDateParser.extractReleaseDateNum("1994, США, Драма")
        assertEquals(19940000, classicFilm)
    }

    @Test
    fun movieDateParser_searchSort_ordersFromNewestToOldest() {
        val item1994 = com.example.data.RezkaItem(
            id = "100-pulp-fiction-1994",
            title = "Криминальное чтиво",
            subtitle = "1994, США, Криминал",
            imageUrl = "",
            url = "/films/crime/100-pulp-fiction-1994.html",
            type = com.example.data.RezkaType.MOVIE
        )
        val item2021 = com.example.data.RezkaItem(
            id = "200-dune-2021",
            title = "Дюна",
            subtitle = "2021, США, Фантастика",
            imageUrl = "",
            url = "/films/fiction/200-dune-2021.html",
            type = com.example.data.RezkaType.MOVIE
        )
        val item2024OlderId = com.example.data.RezkaItem(
            id = "500-movie-a-2024",
            title = "Фильм А",
            subtitle = "2024, США, Комедия",
            imageUrl = "",
            url = "/films/comedy/500-movie-a-2024.html",
            type = com.example.data.RezkaType.MOVIE
        )
        val item2024NewerId = com.example.data.RezkaItem(
            id = "600-movie-b-2024",
            title = "Фильм Б",
            subtitle = "2024, США, Боевик",
            imageUrl = "",
            url = "/films/action/600-movie-b-2024.html",
            type = com.example.data.RezkaType.MOVIE
        )
        val item2025 = com.example.data.RezkaItem(
            id = "700-future-movie-2025",
            title = "Фильм Будущего",
            subtitle = "2025, Франция, Фантастика",
            imageUrl = "",
            url = "/films/fiction/700-future-movie-2025.html",
            type = com.example.data.RezkaType.MOVIE
        )

        val unorganizedList = listOf(item1994, item2024OlderId, item2025, item2021, item2024NewerId)
        val sortedList = unorganizedList.sortedWith(com.example.data.MovieDateParser.MovieDateComparator)

        // Порядок должен быть:
        // 1. 2025
        // 2. 2024 (с большим ID: 600)
        // 3. 2024 (с меньшим ID: 500)
        // 4. 2021
        // 5. 1994
        assertEquals("Первым должен идти самый новый фильм 2025 года", item2025.id, sortedList[0].id)
        assertEquals("Вторым должен идти фильм 2024 года с более свежим ID", item2024NewerId.id, sortedList[1].id)
        assertEquals("Третьим должен идти фильм 2024 года", item2024OlderId.id, sortedList[2].id)
        assertEquals("Четвертым должен идти фильм 2021 года", item2021.id, sortedList[3].id)
        assertEquals("Последним должен идти самый старый фильм 1994 года", item1994.id, sortedList[4].id)
    }
}
