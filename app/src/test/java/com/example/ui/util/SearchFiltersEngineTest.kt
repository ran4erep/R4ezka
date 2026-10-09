package com.example.ui.util

import com.example.data.*
import com.example.ui.components.filterCountryItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFiltersEngineTest {

    @Test
    fun testTypeMatching() {
        val movie = RezkaItem(
            id = "1",
            title = "Начало",
            subtitle = "2010, США, Боевики, Фантастика",
            imageUrl = "",
            url = "https://hdrezka.ag/films/fiction/1-inception.html",
            type = RezkaType.MOVIE
        )
        val series = RezkaItem(
            id = "2",
            title = "Во все тяжкие",
            subtitle = "2008-2013, США, Драмы, Криминал",
            imageUrl = "",
            url = "https://hdrezka.ag/series/drama/2-breaking-bad.html",
            type = RezkaType.SERIES
        )
        val anime = RezkaItem(
            id = "3",
            title = "Атака титанов",
            subtitle = "2013-2023, Япония, Аниме, Боевики",
            imageUrl = "",
            url = "https://hdrezka.ag/animation/action/3-attack-on-titan.html",
            type = RezkaType.ANIME
        )

        // null type matches everything
        assertTrue(movie.matchesType(null))
        assertTrue(series.matchesType(null))
        assertTrue(anime.matchesType(null))

        // Specific types match correctly
        assertTrue(movie.matchesType(RezkaType.MOVIE))
        assertFalse(movie.matchesType(RezkaType.SERIES))

        assertTrue(series.matchesType(RezkaType.SERIES))
        assertFalse(series.matchesType(RezkaType.MOVIE))

        assertTrue(anime.matchesType(RezkaType.ANIME))
        assertFalse(anime.matchesType(RezkaType.MOVIE))
    }

    @Test
    fun testYearMatching() {
        val singleYearItem = RezkaItem(
            id = "1",
            title = "Оппенгеймер",
            subtitle = "2023, США, Биография, Драмы",
            imageUrl = "",
            url = "",
            type = RezkaType.MOVIE
        )
        val rangeYearItem = RezkaItem(
            id = "2",
            title = "Очень странные дела",
            subtitle = "2016-2025, США, Ужасы, Фантастика",
            imageUrl = "",
            url = "",
            type = RezkaType.SERIES
        )

        assertTrue(singleYearItem.matchesYear("2023"))
        assertFalse(singleYearItem.matchesYear("2022"))
        assertTrue(singleYearItem.matchesYear("")) // empty = matches all

        // Range includes all years between 2016 and 2025
        assertTrue(rangeYearItem.matchesYear("2016"))
        assertTrue(rangeYearItem.matchesYear("2020"))
        assertTrue(rangeYearItem.matchesYear("2025"))
        assertFalse(rangeYearItem.matchesYear("2010"))
    }

    @Test
    fun testCountryMatching() {
        val usaItem = RezkaItem(
            id = "1",
            title = "Фильм",
            subtitle = "2024, США, Боевики",
            imageUrl = "",
            url = "",
            type = RezkaType.MOVIE
        )
        val japanItem = RezkaItem(
            id = "2",
            title = "Аниме",
            subtitle = "2024, Япония, Приключения",
            imageUrl = "",
            url = "",
            type = RezkaType.ANIME
        )

        assertTrue(usaItem.matchesCountry("США"))
        assertFalse(usaItem.matchesCountry("Япония"))

        assertTrue(japanItem.matchesCountry("Япония"))
        assertFalse(japanItem.matchesCountry("США"))

        assertTrue(usaItem.matchesCountry("")) // empty = all countries
    }

    @Test
    fun testGenreMatching() {
        val actionItem = RezkaItem(
            id = "1",
            title = "Джон Уик",
            subtitle = "2014, США, Боевики, Триллеры",
            imageUrl = "",
            url = "https://hdrezka.ag/films/action/123-john-wick.html",
            type = RezkaType.MOVIE
        )

        assertTrue(actionItem.matchesGenre("action", "Боевики"))
        assertTrue(actionItem.matchesGenre("", "Боевики"))
        assertTrue(actionItem.matchesGenre("action", ""))
        assertTrue(actionItem.matchesGenre("", ""))
        assertFalse(actionItem.matchesGenre("comedy", "Комедии"))
    }

    @Test
    fun testFilterCountryItems() {
        val countries = listOf(
            CountryItem("🇺🇸 США", "США"),
            CountryItem("🇯🇵 Япония", "Япония"),
            CountryItem("🇰🇷 Южная Корея", "Южная Корея"),
            CountryItem("🇩🇪 Германия", "Германия"),
            CountryItem("🇫🇷 Франция", "Франция")
        )

        // Empty query returns all
        assertEquals(5, filterCountryItems(countries, "").size)

        // Search "яп" returns Japan
        val japanResults = filterCountryItems(countries, "яп")
        assertEquals(1, japanResults.size)
        assertEquals("Япония", japanResults[0].query)

        // Search "кор" returns South Korea
        val koreaResults = filterCountryItems(countries, "кор")
        assertEquals(1, koreaResults.size)
        assertEquals("Южная Корея", koreaResults[0].query)

        // Search "сша" returns USA
        val usaResults = filterCountryItems(countries, "сша")
        assertEquals(1, usaResults.size)
        assertEquals("США", usaResults[0].query)
    }

    @Test
    fun testSearchHistoryDeletionAndClearLogic() {
        val initialHistory = mutableListOf("Интерстеллар", "Матрица", "Начало", "Джентльмены")
        
        // Удаление конкретного элемента
        val queryToRemove = "Матрица"
        initialHistory.removeAll { it.equals(queryToRemove, ignoreCase = true) }
        assertEquals(listOf("Интерстеллар", "Начало", "Джентльмены"), initialHistory)

        // Регистронезависимое удаление
        val queryCaseInsensitive = "начало"
        initialHistory.removeAll { it.equals(queryCaseInsensitive, ignoreCase = true) }
        assertEquals(listOf("Интерстеллар", "Джентльмены"), initialHistory)

        // Очистка всей истории
        initialHistory.clear()
        assertTrue(initialHistory.isEmpty())
    }
}
