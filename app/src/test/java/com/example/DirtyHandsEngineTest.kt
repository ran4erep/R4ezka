package com.example

import com.example.data.DirtyHandsAction
import com.example.data.DirtyHandsEngine
import com.example.data.VoiceCommandTemplate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Модульные тесты для высокопроизводительного движка голосового управления «Грязные руки» (DirtyHandsEngine).
 * Проверяют корректность ZLIB сжатия и декомпрессии шаблонов для облака, целостность данных
 * и соответствие форматов.
 */
class DirtyHandsEngineTest {

    @Test
    fun testActionFromId() {
        assertEquals(DirtyHandsAction.PAUSE, DirtyHandsAction.fromId("pause"))
        assertEquals(DirtyHandsAction.RESUME, DirtyHandsAction.fromId("resume"))
        assertEquals(DirtyHandsAction.NEXT_EPISODE, DirtyHandsAction.fromId("next_episode"))
        assertEquals(DirtyHandsAction.PREV_EPISODE, DirtyHandsAction.fromId("prev_episode"))
        assertEquals(DirtyHandsAction.SEEK_FORWARD, DirtyHandsAction.fromId("seek_forward"))
        assertEquals(DirtyHandsAction.SEEK_BACKWARD, DirtyHandsAction.fromId("seek_backward"))
    }

    @Test
    fun testCompressionAndDecompressionIntegrity() {
        val frameCount = 60
        val featureDim = 13
        val dummyFeatures = ByteArray(frameCount * featureDim) { index ->
            ((index * 7) % 255 - 128).toByte()
        }

        val dummyPcm = ByteArray(16000 * 2) { index ->
            (index % 120).toByte()
        }

        val originalTemplate = VoiceCommandTemplate(
            action = DirtyHandsAction.PAUSE,
            frameCount = frameCount,
            featureDim = featureDim,
            features = dummyFeatures,
            audioSample = dummyPcm,
            durationMs = 1000,
            createdAt = 1700000000000L
        )

        // 1. Сжатие в компактный Base64 пакет
        val compressedBase64 = DirtyHandsEngine.serializeAndCompressTemplate(originalTemplate)
        assertNotNull(compressedBase64)
        assertTrue("Сжатый пакет должен быть не пустым", compressedBase64.isNotEmpty())

        // Проверяем, что сжатый пакет занимает очень мало места
        // Исходные данные: 32000 байт PCM + 780 байт фичей = ~33 КБ.
        // Сжатый ZLIB блоб в Base64 должен быть в разы меньше исходного PCM!
        assertTrue("Размер сжатого Base64 пакета должен быть оптимизирован для сети", compressedBase64.length < 5000)

        // 2. Разжатие на устройстве
        val restoredTemplate = DirtyHandsEngine.deserializeCompressedTemplate(
            action = DirtyHandsAction.PAUSE,
            compressedBase64 = compressedBase64
        )

        assertNotNull("Восстановленный шаблон не должен быть null", restoredTemplate)
        assertEquals(DirtyHandsAction.PAUSE, restoredTemplate!!.action)
        assertEquals(frameCount, restoredTemplate.frameCount)
        assertEquals(featureDim, restoredTemplate.featureDim)
        assertEquals(1000, restoredTemplate.durationMs)
        assertEquals(1700000000000L, restoredTemplate.createdAt)
        assertArrayEquals("Признаки MFCC должны восстанавливаться байт-в-байт", dummyFeatures, restoredTemplate.features)
        assertNotNull(restoredTemplate.audioSample)
        assertArrayEquals("Звуковой образец PCM должен восстанавливаться байт-в-байт", dummyPcm, restoredTemplate.audioSample)
    }

    @Test
    fun testAllActionsHaveDescriptionsAndTitles() {
        for (action in DirtyHandsAction.entries) {
            assertTrue("Название действия не должно быть пустым", action.title.isNotBlank())
            assertTrue("Описание действия не должно быть пустым", action.description.isNotBlank())
            assertTrue("Подсказка по умолчанию не должна быть пустой", action.defaultKeywordSuggestion.isNotBlank())
        }
    }
}
