package com.example.ui.tv

import com.example.ui.TvDetailSavedFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TvFocusRestorationTest {

    @Test
    fun testTvFocusRegistryReturnsSameRequesterForSameKey() {
        val registry = TvFocusRegistry()
        val key = "link_director_christopher_nolan"

        val req1 = registry.get(key)
        val req2 = registry.get(key)

        assertNotNull(req1)
        assertSame("Должен возвращаться тот же экземпляр FocusRequester для одного ключа", req1, req2)
    }

    @Test
    fun testTvFocusRegistryFind() {
        val registry = TvFocusRegistry()
        val key = "actor_cillian_murphy"

        assertNull("До регистрации поиск должен возвращать null", registry.find(key))

        val created = registry.get(key)
        val found = registry.find(key)

        assertNotNull(found)
        assertSame(created, found)
    }

    @Test
    fun testTvDetailSavedFocusDataModel() {
        val focus = TvDetailSavedFocus(
            focusKey = "link_franchise_123",
            listIndex = 4
        )

        assertEquals("link_franchise_123", focus.focusKey)
        assertEquals(4, focus.listIndex)
    }
}
