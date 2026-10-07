package com.example.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSearchEngineTest {

    @Test
    fun testCleanRecognizedTextNull() {
        val result = VoiceSearchEngine.cleanRecognizedText(null)
        assertNull(result)
    }

    @Test
    fun testCleanRecognizedTextEmptyList() {
        val result = VoiceSearchEngine.cleanRecognizedText(emptyList())
        assertNull(result)
    }

    @Test
    fun testCleanRecognizedTextValidCandidates() {
        val candidates = listOf("   Интерстеллар   ", "Интерстеллар фильм")
        val result = VoiceSearchEngine.cleanRecognizedText(candidates)
        assertNotNull(result)
        assertEquals("Интерстеллар", result)
    }

    @Test
    fun testCleanRecognizedTextBlankCandidatesSkipped() {
        val candidates = listOf("   ", "\t\n", "Дюна 2", "Дюна")
        val result = VoiceSearchEngine.cleanRecognizedText(candidates)
        assertNotNull(result)
        assertEquals("Дюна 2", result)
    }

    @Test
    fun testCleanRecognizedTextAllBlank() {
        val candidates = listOf("   ", "\t", "  \n  ")
        val result = VoiceSearchEngine.cleanRecognizedText(candidates)
        assertNull(result)
    }
}
