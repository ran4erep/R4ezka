package com.example.ui.util

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterBadgeEngineTest {

    @Test
    fun testBadgeVariantsParsing() {
        val full = "4 сезон 16 серия (HDrezka Studio)"
        val variants = PosterBadgeEngine.getVariants(full)

        assertEquals("4 сезон 16 серия (HDrezka Studio)", variants.full)
        assertEquals("4 сезон 16 серия", variants.clean)
        assertEquals("4с 16с", variants.compact)
        assertEquals("4с·16", variants.micro)
    }

    @Test
    fun testSeasonOnlyVariants() {
        val variants = PosterBadgeEngine.getVariants("2 сезон (LostFilm)")
        assertEquals("2 сезон", variants.clean)
        assertEquals("2с", variants.compact)
    }

    @Test
    fun testEpisodeOnlyVariants() {
        val variants = PosterBadgeEngine.getVariants("8 серия (Кубик в Кубе)")
        assertEquals("8 серия", variants.clean)
        assertEquals("8с", variants.compact)
    }

    @Test
    fun testDisabledSeriesBadgeMode() {
        val style = PosterBadgeEngine.resolveStyle(
            rawText = "1 сезон 5 серия",
            isSeries = true,
            seriesBadgeMode = SeriesBadgeMode.DISABLED,
            columnsCount = 2
        )
        assertFalse("Плашка сериала должна быть отключена при DISABLED", style.visible)
    }

    @Test
    fun testMovieBadgeHiddenByDefault() {
        val style = PosterBadgeEngine.resolveStyle(
            rawText = "8.4",
            isSeries = false,
            seriesBadgeMode = SeriesBadgeMode.ADAPTIVE,
            columnsCount = 2
        )
        assertFalse("На фильмах плашки не отображаются", style.visible)
    }

    @Test
    fun testAdaptiveBehaviorOnSmallPoster() {
        val style = PosterBadgeEngine.resolveStyle(
            rawText = "3 сезон 24 серия (LostFilm)",
            isSeries = true,
            seriesBadgeMode = SeriesBadgeMode.ADAPTIVE,
            cardWidth = 68.dp,
            columnsCount = 6
        )

        assertTrue(style.visible)
        assertEquals("3с 24с", style.text)
        assertTrue("Шрифт должен быть уменьшен под мелкий постер", style.fontSize.value <= 8f)
    }

    @Test
    fun testAutoHidingOnCriticallySmallPoster() {
        val style = PosterBadgeEngine.resolveStyle(
            rawText = "3 сезон 10 серия",
            isSeries = true,
            seriesBadgeMode = SeriesBadgeMode.ADAPTIVE,
            cardWidth = 40.dp,
            cardHeight = 50.dp,
            columnsCount = 10
        )

        assertFalse("Плашка должна скрываться на микро-карточке, чтобы не закрывать всё изображение", style.visible)
    }

    @Test
    fun testAlwaysCompactMode() {
        val style = PosterBadgeEngine.resolveStyle(
            rawText = "1 сезон 12 серия",
            isSeries = true,
            seriesBadgeMode = SeriesBadgeMode.COMPACT,
            cardWidth = 200.dp,
            columnsCount = 2
        )

        assertTrue(style.visible)
        assertEquals("1с 12с", style.text)
    }
}
