package com.lagradost.hless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StremioIdTest {
    @Test
    fun `movie id parses`() {
        assertEquals(StremioId("tt1111111", null, null), parseStremioId("movie", "tt1111111"))
    }

    @Test
    fun `series id parses`() {
        assertEquals(StremioId("tt9944947", 1, 2), parseStremioId("series", "tt9944947:1:2"))
    }

    @Test
    fun `movie id with colon is rejected`() {
        assertNull(parseStremioId("movie", "tt9944947:1:2"))
    }

    @Test
    fun `series id without colon is rejected`() {
        assertNull(parseStremioId("series", "tt9944947"))
    }

    @Test
    fun `series id with bad parts is rejected`() {
        assertNull(parseStremioId("series", "tt9944947:x:2"))
        assertNull(parseStremioId("series", "tt9944947:1"))
        assertNull(parseStremioId("series", "tt9944947:1:2:3"))
    }

    @Test
    fun `non tt imdb is rejected`() {
        assertNull(parseStremioId("movie", "tm1111111"))
        assertNull(parseStremioId("movie", "tt111abc"))
        assertNull(parseStremioId("series", "tm1111111:1:2"))
    }

    @Test
    fun `unknown type is rejected`() {
        assertNull(parseStremioId("channel", "tt1111111"))
    }
}
