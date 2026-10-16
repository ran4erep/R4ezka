package com.example
 
import com.example.data.*
import org.junit.Assert.*
import org.junit.Test

class FilterPreservationAndInterleavingTest {

    @Test
    fun testBuildCatalogUrlDoesNotForceFilmsForNullCategory() {
        // Проверяем, что для "Все категории" (null) URL не содержит /films/
        val url = RezkaService.buildCatalogUrl(
            type = null,
            section = SectionType.LATEST,
            genre = "comedy",
            year = "2023",
            page = 1,
            baseUrl = "https://hdrezka.me"
        )
        assertFalse("URL не должен принудительно содержать /films/ при type == null", url.contains("/films/"))
        assertTrue("URL должен содержать корень хоста", url.startsWith("https://hdrezka.me/"))
    }

    @Test
    fun testBuildCatalogUrlForSpecificCategories() {
        val seriesUrl = RezkaService.buildCatalogUrl(
            type = RezkaType.SERIES,
            section = SectionType.LATEST,
            genre = "comedy",
            year = "2024",
            page = 1,
            baseUrl = "https://hdrezka.me"
        )
        assertTrue(seriesUrl.contains("/series/best/comedy/2024/"))

        val animeUrl = RezkaService.buildCatalogUrl(
            type = RezkaType.ANIME,
            section = SectionType.POPULAR,
            genre = "",
            year = "2022",
            page = 2,
            baseUrl = "https://hdrezka.me"
        )
        assertTrue(animeUrl.contains("/animation/best/2022/page/2/?filter=popular"))
    }

    @Test
    fun testValidateOrResolveGenrePreservesCommonGenreAcrossCategories() {
        // Общеупотребительный жанр "comedy" должен сохраняться при переходе между всеми категориями
        assertEquals("comedy", RezkaService.validateOrResolveGenre(RezkaType.SERIES, "comedy"))
        assertEquals("comedy", RezkaService.validateOrResolveGenre(RezkaType.MOVIE, "comedy"))
        assertEquals("comedy", RezkaService.validateOrResolveGenre(RezkaType.CARTOON, "comedy"))
        assertEquals("comedy", RezkaService.validateOrResolveGenre(RezkaType.ANIME, "comedy"))
        assertEquals("comedy", RezkaService.validateOrResolveGenre(null, "comedy"))
    }

    @Test
    fun testInterleaveOrSortCatalogItemsIncludesAllCategories() {
        val movies = listOf(
            RezkaItem("m1", "Фильм 1", "2023, США, Фильмы", "", "8.1", "https://hdrezka.me/films/1.html", RezkaType.MOVIE),
            RezkaItem("m2", "Фильм 2", "2023, Франция, Фильмы", "", "7.5", "https://hdrezka.me/films/2.html", RezkaType.MOVIE)
        )
        val series = listOf(
            RezkaItem("s1", "Сериал 1", "2023, США, Сериалы", "", "8.9", "https://hdrezka.me/series/3.html", RezkaType.SERIES),
            RezkaItem("s2", "Сериал 2", "2023, Великобритания, Сериалы", "", "8.0", "https://hdrezka.me/series/4.html", RezkaType.SERIES)
        )
        val cartoons = listOf(
            RezkaItem("c1", "Мультик 1", "2023, США, Мультфильмы", "", "8.2", "https://hdrezka.me/cartoons/5.html", RezkaType.CARTOON)
        )
        val anime = listOf(
            RezkaItem("a1", "Аниме 1", "2023, Япония, Аниме", "", "8.7", "https://hdrezka.me/animation/6.html", RezkaType.ANIME)
        )

        // Тестируем слияние Round-Robin для WATCHING / AWAITING
        val merged = RezkaService.interleaveOrSortCatalogItems(
            listOf(movies, series, cartoons, anime),
            SectionType.WATCHING
        )

        assertEquals("Должны присутствовать все 6 уникальных элементов", 6, merged.size)

        // Проверяем, что в результирующем списке есть представители ВСЕХ 4 категорий
        val types = merged.map { it.type }.toSet()
        assertTrue("Должны быть фильмы", types.contains(RezkaType.MOVIE))
        assertTrue("Должны быть сериалы", types.contains(RezkaType.SERIES))
        assertTrue("Должны быть мультфильмы", types.contains(RezkaType.CARTOON))
        assertTrue("Должно быть аниме", types.contains(RezkaType.ANIME))

        // Проверяем чередование: первые 4 элемента принадлежат 4 разным категориям
        val firstFourTypes = merged.take(4).map { it.type }
        assertEquals(4, firstFourTypes.distinct().size)
    }

    @Test
    fun testCommonGenresNotEmpty() {
        assertTrue(RezkaService.COMMON_GENRES.isNotEmpty())
        val genreSlugs = RezkaService.COMMON_GENRES.map { it.slug }
        assertTrue(genreSlugs.contains("comedy"))
        assertTrue(genreSlugs.contains("action"))
        assertTrue(genreSlugs.contains("drama"))
        assertTrue(genreSlugs.contains("sci-fi"))
    }

    @Test
    fun testDefaultGenreAndCountrySetting() {
        // Проверяем установку и чтение жанра по умолчанию
        RezkaService.setDefaultCatalogGenre("action")
        assertEquals("action", RezkaService.defaultCatalogGenre.value)

        RezkaService.setDefaultCatalogGenre("")
        assertEquals("", RezkaService.defaultCatalogGenre.value)

        // Проверяем установку и чтение страны по умолчанию
        RezkaService.setDefaultCatalogCountry("США")
        assertEquals("США", RezkaService.defaultCatalogCountry.value)

        RezkaService.setDefaultCatalogCountry("")
        assertEquals("", RezkaService.defaultCatalogCountry.value)
    }
}
