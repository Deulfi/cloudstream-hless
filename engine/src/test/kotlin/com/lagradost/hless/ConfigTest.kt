package com.lagradost.hless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {

    @Test
    fun `defaults when env is empty`() {
        val config = ConfigValues(emptyMap())
        assertEquals(7000, config.port)
        assertEquals(17171, config.enginePort)
        assertEquals(null, config.secret)
        assertEquals(null, config.publicUrl)
        assertEquals(emptyList(), config.repos)
        assertEquals(listOf("*"), config.plugins)
        assertEquals(15_000L, config.deadlineMs)
        assertEquals(null, config.mediaflowUrl)
        assertEquals(null, config.mediaflowPassword)
        assertEquals(null, config.tmdbKey)
        assertEquals(listOf("en"), config.langs)
        assertEquals(0, config.minQuality)
        assertEquals(4, config.maxPerProvider)
        assertEquals(30, config.maxStreams)
        assertEquals(emptyList(), config.providersDeny)
        assertEquals(emptyList(), config.providersAllow)
        assertEquals(emptyList(), config.providersFirst)
    }

    @Test
    fun `lists split on comma, trim entries, drop empties`() {
        val config = ConfigValues(
            mapOf(
                "HLESS_LANGS" to " en , de,,fr ",
                "HLESS_REPOS" to "https://a.example/repo.json, https://b.example/repo.json",
                "HLESS_PLUGINS" to "MovieProvider, SeriesProvider",
                "HLESS_PROVIDERS_DENY" to "Bad, Worse",
            ),
        )
        assertEquals(listOf("en", "de", "fr"), config.langs)
        assertEquals(
            listOf("https://a.example/repo.json", "https://b.example/repo.json"),
            config.repos,
        )
        assertEquals(listOf("MovieProvider", "SeriesProvider"), config.plugins)
        assertEquals(listOf("Bad", "Worse"), config.providersDeny)
    }

    @Test
    fun `URL values lose one trailing slash`() {
        val config = ConfigValues(
        mapOf(
            "HLESS_PUBLIC_URL" to "https://example.com/",
            "HLESS_REPOS" to "https://a.example/repo.json/",
            "HLESS_MEDIAFLOW_URL" to "http://mf.example/",
        ),
        )
        assertEquals("https://example.com", config.publicUrl)
        assertEquals(listOf("https://a.example/repo.json"), config.repos)
        assertEquals("http://mf.example", config.mediaflowUrl)
    }

    @Test
    fun `empty string means unset`() {
        val config = ConfigValues(mapOf("HLESS_SECRET" to "   ", "HLESS_TMDB_KEY" to ""))
        assertEquals(null, config.secret)
        assertEquals(null, config.tmdbKey)
    }

    @Test
    fun `non-numeric numbers are a startup error`() {
        val failure = assertFailsWith<IllegalStateException> {
            ConfigValues(mapOf("HLESS_PORT" to "abc"))
        }
        assertEquals(true, failure.message!!.contains("HLESS_PORT"))
    }

    @Test
    fun `numbers parse`() {
        val config = ConfigValues(
            mapOf(
                "HLESS_PORT" to "8080",
                "HLESS_DEADLINE_MS" to "25000",
                "HLESS_MIN_QUALITY" to "720",
                "HLESS_MAX_STREAMS" to "12",
            ),
        )
        assertEquals(8080, config.port)
        assertEquals(25_000L, config.deadlineMs)
        assertEquals(720, config.minQuality)
        assertEquals(12, config.maxStreams)
    }
}
