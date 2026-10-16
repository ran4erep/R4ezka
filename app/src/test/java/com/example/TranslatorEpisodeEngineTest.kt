package com.example

import com.example.data.Episode
import com.example.data.Season
import com.example.data.Translator
import com.example.data.TranslatorEpisodeEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TranslatorEpisodeEngineTest {

    @Before
    fun setup() {
        TranslatorEpisodeEngine.clearCache()
    }

    @Test
    fun testExtractNumbers() {
        assertEquals(listOf(1), TranslatorEpisodeEngine.extractNumbers("1 серия"))
        assertEquals(listOf(2), TranslatorEpisodeEngine.extractNumbers("2 серия"))
        assertEquals(listOf(1, 2), TranslatorEpisodeEngine.extractNumbers("1-2 серия"))
        assertEquals(listOf(10, 15), TranslatorEpisodeEngine.extractNumbers("сезон 10 серия 15"))
        assertEquals(listOf(42), TranslatorEpisodeEngine.extractNumbers("42"))
        assertEquals(emptyList<Int>(), TranslatorEpisodeEngine.extractNumbers("Пилотный выпуск"))
    }

    @Test
    fun testUserScenario_Episode2MissingInOneTranslator() {
        // Ситуация пользователя:
        // Озвучка 1 (HDRezka): есть 1 и 2 серия
        // Озвучка 2 (Кубик в Кубе): есть ТОЛЬКО 1 серия
        val trans1 = Translator(id = "1", name = "HDRezka Studio")
        val trans2 = Translator(id = "2", name = "Кубик в Кубе")
        val allTranslators = listOf(trans1, trans2)

        val seasonsTrans1 = listOf(
            Season(
                id = 1,
                name = "1 сезон",
                episodes = listOf(
                    Episode(id = "1", name = "1 серия", seasonId = 1, translatorId = "1"),
                    Episode(id = "2", name = "2 серия", seasonId = 1, translatorId = "1")
                )
            )
        )

        val seasonsTrans2 = listOf(
            Season(
                id = 1,
                name = "1 сезон",
                episodes = listOf(
                    Episode(id = "1", name = "1 серия", seasonId = 1, translatorId = "2")
                )
            )
        )

        val postId = "55555"
        TranslatorEpisodeEngine.registerSeasons(postId, trans1.id, seasonsTrans1)
        TranslatorEpisodeEngine.registerSeasons(postId, trans2.id, seasonsTrans2)

        // 1. Воспроизводится 1 серия: ОБЕ озвучки должны быть доступны!
        val availableForEp1 = TranslatorEpisodeEngine.filterTranslatorsForEpisode(
            numericPostId = postId,
            allTranslators = allTranslators,
            currentTranslator = trans1,
            currentSeasonId = 1,
            currentSeasonName = "1 сезон",
            currentEpisodeId = "1",
            currentEpisodeName = "1 серия",
            currentEpisodeIndex = 0,
            isSeries = true
        )
        assertEquals(2, availableForEp1.size)
        assertTrue(availableForEp1.any { it.id == "1" })
        assertTrue(availableForEp1.any { it.id == "2" })

        // 2. Включаем 2 серию: Озвучка 2 (Кубик в Кубе) НЕ ДОЛЖНА отображаться, так как у нее нет 2 серии!
        val availableForEp2 = TranslatorEpisodeEngine.filterTranslatorsForEpisode(
            numericPostId = postId,
            allTranslators = allTranslators,
            currentTranslator = trans1,
            currentSeasonId = 1,
            currentSeasonName = "1 сезон",
            currentEpisodeId = "2",
            currentEpisodeName = "2 серия",
            currentEpisodeIndex = 1,
            isSeries = true
        )
        assertEquals(1, availableForEp2.size)
        assertEquals("1", availableForEp2[0].id)
        assertEquals("HDRezka Studio", availableForEp2[0].name)
    }

    @Test
    fun testSeasonMissingInTranslator() {
        val transA = Translator(id = "10", name = "LostFilm")
        val transB = Translator(id = "20", name = "NewStudio")
        val allTranslators = listOf(transA, transB)

        // LostFilm перевел 1 и 2 сезон
        val seasonsA = listOf(
            Season(id = 1, name = "1 сезон", episodes = listOf(Episode("1", "1 серия", 1))),
            Season(id = 2, name = "2 сезон", episodes = listOf(Episode("1", "1 серия", 2)))
        )

        // NewStudio перевел ТОЛЬКО 1 сезон
        val seasonsB = listOf(
            Season(id = 1, name = "1 сезон", episodes = listOf(Episode("1", "1 серия", 1)))
        )

        val postId = "777"
        TranslatorEpisodeEngine.registerSeasons(postId, transA.id, seasonsA)
        TranslatorEpisodeEngine.registerSeasons(postId, transB.id, seasonsB)

        // Смотрим 2 сезон 1 серию: NewStudio не должен быть доступен
        val available = TranslatorEpisodeEngine.filterTranslatorsForEpisode(
            numericPostId = postId,
            allTranslators = allTranslators,
            currentTranslator = transA,
            currentSeasonId = 2,
            currentSeasonName = "2 сезон",
            currentEpisodeId = "1",
            currentEpisodeName = "1 серия",
            currentEpisodeIndex = 0,
            isSeries = true
        )
        assertEquals(1, available.size)
        assertEquals("10", available[0].id)
    }

    @Test
    fun testMovieIsNotFiltered() {
        val trans1 = Translator("1", "Дубляж")
        val trans2 = Translator("2", "Закадровый")
        val all = listOf(trans1, trans2)

        // Для фильмов isSeries = false, возвращаются все озвучки
        val available = TranslatorEpisodeEngine.filterTranslatorsForEpisode(
            numericPostId = "888",
            allTranslators = all,
            currentTranslator = trans1,
            currentSeasonId = 0,
            currentSeasonName = "",
            currentEpisodeId = "",
            currentEpisodeName = "",
            currentEpisodeIndex = 0,
            isSeries = false
        )
        assertEquals(2, available.size)
    }

    @Test
    fun testDoubleEpisodeMatching() {
        val trans1 = Translator("1", "Озвучка 1")
        val seasons = listOf(
            Season(
                id = 1,
                name = "1 сезон",
                episodes = listOf(
                    Episode(id = "1-2", name = "1-2 серия", seasonId = 1)
                )
            )
        )
        TranslatorEpisodeEngine.registerSeasons("999", "1", seasons)

        // Проверяем 2-ю серию против сдвоенной серии 1-2
        val hasEp = TranslatorEpisodeEngine.hasEpisode(
            seasons = seasons,
            targetSeasonId = 1,
            targetSeasonName = "1 сезон",
            targetEpisodeId = "2",
            targetEpisodeName = "2 серия",
            targetEpisodeIndex = 1
        )
        assertTrue(hasEp)
    }
}
