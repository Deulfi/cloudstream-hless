package com.lagradost.hless

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `find id comes from the first movie result`() {
        val find = json.parseToJsonElement("""{"movie_results":[{"id":27205,"title":"Inception"}],"tv_results":[]}""")
        assertEquals(27205, Tmdb.findId("movie", find))
    }

    @Test
    fun `find id comes from the first tv result`() {
        val find = json.parseToJsonElement("""{"movie_results":[],"tv_results":[{"id":1396,"name":"Breaking Bad"}]}""")
        assertEquals(1396, Tmdb.findId("series", find))
    }

    @Test
    fun `empty find results give no id`() {
        val find = json.parseToJsonElement("""{"movie_results":[],"tv_results":[]}""")
        assertNull(Tmdb.findId("movie", find))
        assertNull(Tmdb.findId("series", find))
        assertNull(Tmdb.findId("movie", null))
    }

    @Test
    fun `names are preferred language first then original then english`() {
        val find = json.parseToJsonElement(
            """{"movie_results":[{"id":191037,"title":"The Great Beauty","original_title":"La Grande Bellezza","release_date":"2013-10-15"}]}""",
        )
        val translations = json.parseToJsonElement(
            """{"translations":[
                {"iso_639_1":"de","data":{"title":"Die große Schönheit"}},
                {"iso_639_1":"en","data":{"title":"The Great Beauty"}}
            ]}""",
        )
        val info = Tmdb.buildNames("movie", find, translations, listOf("de", "en"))
        assertEquals(listOf("Die große Schönheit", "The Great Beauty", "La Grande Bellezza"), info?.names)
        assertEquals(2013, info?.year)
    }

    @Test
    fun `series uses name and first air date`() {
        val find = json.parseToJsonElement(
            """{"tv_results":[{"id":1396,"name":"Breaking Bad","original_name":"Breaking Bad","first_air_date":"2008-01-20"}]}""",
        )
        val translations = json.parseToJsonElement("""{"translations":[]}""")
        val info = Tmdb.buildNames("series", find, translations, listOf("en"))
        assertEquals(listOf("Breaking Bad"), info?.names)
        assertEquals(2008, info?.year)
    }

    @Test
    fun `empty movie results give no names`() {
        val find = json.parseToJsonElement("""{"movie_results":[]}""")
        val translations = json.parseToJsonElement("""{"translations":[]}""")
        assertNull(Tmdb.buildNames("movie", find, translations, listOf("en")))
    }

    @Test
    fun `appended nested translations are read`() {
        val find = json.parseToJsonElement(
            """{"movie_results":[{"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-30"}]}""",
        )
        val appended = json.parseToJsonElement(
            """{"adult":false,"translations":{"translations":[
                {"iso_639_1":"de","data":{"title":"Matrix"}},
                {"iso_639_1":"en","data":{"title":""}}
            ]}}""",
        )
        val info = Tmdb.buildNames("movie", find, appended, listOf("de", "en"))
        assertEquals(listOf("Matrix", "The Matrix"), info?.names)
        assertEquals(1999, info?.year)
    }
}
