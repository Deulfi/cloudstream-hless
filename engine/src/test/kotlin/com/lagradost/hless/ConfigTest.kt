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
        assertEquals(6, config.maxPerProvider)
        assertEquals(40, config.maxStreams)
        assertEquals(emptyList(), config.providersDeny)
        assertEquals(emptyList(), config.providersAllow)
        assertEquals(emptyList(), config.providersFirst)
        assertEquals(null, config.solverUrl)
        assertEquals(60_000L, config.solverTimeoutMs)
        assertEquals(600_000L, config.solverCooldownMs)
        assertEquals(0, config.solverConcurrency)
        assertEquals("hless", config.label)
        assertEquals(FlagMode.EMOJI, config.flags)
        assertEquals(true, config.icons)
        assertEquals(Variants.FOLD, config.variants)
        assertEquals(0, config.maxQuality)
        assertEquals(SortMode.LANG, config.sort)
        assertEquals(4_000L, config.minWaitMs)
        assertEquals(2_000L, config.quietMs)
    }

    @Test
    fun `display and ordering options parse`() {
        val config = ConfigValues(
            mapOf(
                "HLESS_LABEL" to "mine",
                "HLESS_FLAGS" to "CODE",
                "HLESS_ICONS" to "false",
                "HLESS_VARIANTS" to "all",
                "HLESS_SORT" to "size",
                "HLESS_MAX_QUALITY" to "1080",
                "HLESS_MIN_WAIT_MS" to "1000",
                "HLESS_QUIET_MS" to "500",
            ),
        )
        assertEquals("mine", config.label)
        assertEquals(FlagMode.CODE, config.flags)
        assertEquals(false, config.icons)
        assertEquals(Variants.ALL, config.variants)
        assertEquals(SortMode.SIZE, config.sort)
        assertEquals(1080, config.maxQuality)
        assertEquals(1_000L, config.minWaitMs)
        assertEquals(500L, config.quietMs)
    }

    @Test
    fun `an unknown option value is a startup error naming the variable`() {
        for (env in listOf(
            mapOf("HLESS_FLAGS" to "banana"),
            mapOf("HLESS_SORT" to "random"),
            mapOf("HLESS_VARIANTS" to "some"),
            mapOf("HLESS_ICONS" to "maybe"),
        )) {
            val failure = assertFailsWith<IllegalStateException> { ConfigValues(env) }
            assertEquals(true, failure.message!!.contains(env.keys.single()))
        }
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
            "HLESS_SOLVER_URL" to "http://10.0.0.5:8191/",
        ),
        )
        assertEquals("https://example.com", config.publicUrl)
        assertEquals(listOf("https://a.example/repo.json"), config.repos)
        assertEquals("http://mf.example", config.mediaflowUrl)
        assertEquals("http://10.0.0.5:8191", config.solverUrl)
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
                "HLESS_SOLVER_TIMEOUT_MS" to "90000",
            ),
        )
        assertEquals(8080, config.port)
        assertEquals(25_000L, config.deadlineMs)
        assertEquals(720, config.minQuality)
        assertEquals(12, config.maxStreams)
        assertEquals(90_000L, config.solverTimeoutMs)
    }
}
