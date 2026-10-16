package com.example

import com.example.data.*
import com.example.ui.util.PosterBadgeEngine
import org.junit.Assert.*
import org.junit.Test

class AnimeCartoonBadgeTest {

    @Test
    fun testFeatureLengthAnimeIsNotSeries() {
        val animeMovie = RezkaItem(
            id = "123",
            title = "Унесённые призраками",
            subtitle = "2001, Япония, Аниме, Фильмы",
            imageUrl = "",
            rating = "8.5",
            url = "https://hdrezka.ag/animation/anime/123-spirited-away.html",
            type = RezkaType.ANIME
        )
        assertFalse(animeMovie.isSeriesContent())
    }

    @Test
    fun testAnimeSeriesIsSeries() {
        val animeSeries = RezkaItem(
            id = "456",
            title = "Атака титанов",
            subtitle = "2013, Япония, Аниме, Сериалы",
            imageUrl = "",
            rating = "4 сезон 28 серия",
            url = "https://hdrezka.ag/animation/anime/456-shingeki-no-kyojin.html",
            type = RezkaType.ANIME
        )
        assertTrue(animeSeries.isSeriesContent())
    }

    @Test
    fun testFeatureLengthCartoonIsNotSeries() {
        val cartoonMovie = RezkaItem(
            id = "789",
            title = "Шрек",
            subtitle = "2001, США, Мультфильмы, Фильмы",
            imageUrl = "",
            rating = "8.1",
            url = "https://hdrezka.ag/cartoons/comedy/789-shrek.html",
            type = RezkaType.CARTOON
        )
        assertFalse(cartoonMovie.isSeriesContent())
    }

    @Test
    fun testCartoonSeriesIsSeries() {
        val cartoonSeries = RezkaItem(
            id = "101",
            title = "Рик и Морти",
            subtitle = "2013, США, Мультфильмы, Сериалы",
            imageUrl = "",
            rating = "7 сезон 10 серия",
            url = "https://hdrezka.ag/cartoons/comedy/101-rick-and-morty.html",
            type = RezkaType.CARTOON
        )
        assertTrue(cartoonSeries.isSeriesContent())
    }

    @Test
    fun testAwaitingReleaseBadge() {
        val variants = PosterBadgeEngine.getVariants("В ожидании выхода")
        assertEquals("В ожидании выхода", variants.clean)
        assertEquals("В ожидании", variants.compact)
        assertTrue(PosterBadgeEngine.isAwaiting("В ожидании выхода"))
        assertTrue(PosterBadgeEngine.isAwaiting("В ожидании"))

        val style = PosterBadgeEngine.resolveStyle(
            rawText = "В ожидании выхода",
            isSeries = false
        )
        assertTrue(style.visible)
        assertEquals("В ожидании выхода", style.text)
    }

    @Test
    fun testUnreleasedSeriesHtmlDoesNotParseScheduleAsReleased() {
        val unreleasedHtml = """
            <html>
                <body>
                    <div class="b-post__status_awaiting">В ожидании выхода</div>
                    <table class="b-post__schedule_table">
                        <tr><td>1 сезон 1 серия</td><td>25 ноября 2026</td></tr>
                    </table>
                </body>
            </html>
        """.trimIndent()

        val scanResult = SeriesUpdateEngine.parseLatestEpisodeFromHtml(unreleasedHtml)
        assertTrue(scanResult.isAwaitingRelease)
        assertEquals(0, scanResult.latestSeason)
        assertEquals(0, scanResult.latestEpisode)
        assertEquals("В ожидании выхода", scanResult.latestEpisodeName)
    }
}
