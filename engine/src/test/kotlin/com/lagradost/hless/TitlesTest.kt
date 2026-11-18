package com.lagradost.hless

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TitlesTest {
    @Test
    fun `tmdb failure falls back to cinemeta`() = runBlocking {
            val store = JsonFileStore(File(createTempDirectory("hless-titles").toString(), "titles.json"))
        val cinemetaCalls = AtomicInteger(0)
        val src = TitleSource(
            store = store,
            tmdbKey = "k",
            langs = listOf("en"),
            tmdb = { _, _, _, _ -> null },
            cinemeta = { _, _ ->
                cinemetaCalls.incrementAndGet()
                TitleInfo(listOf("Cinemeta"), 2001)
            },
        )
        assertEquals(TitleInfo(listOf("Cinemeta"), 2001), src.lookup("movie", "tt1"))
        assertEquals(1, cinemetaCalls.get())
        // cached: cinemeta is not called again
        assertEquals(TitleInfo(listOf("Cinemeta"), 2001), src.lookup("movie", "tt1"))
        assertEquals(1, cinemetaCalls.get())
    }

    @Test
    fun `cache key includes the language list`() = runBlocking {
            val shared = JsonFileStore(File(createTempDirectory("hless-titles").toString(), "titles.json"))
        val cinemetaCalls = AtomicInteger(0)
        fun source(langs: List<String>) = TitleSource(
            store = shared,
            tmdbKey = null,
            langs = langs,
            tmdb = { _, _, _, _ -> null },
            cinemeta = { _, _ ->
                cinemetaCalls.incrementAndGet()
                TitleInfo(listOf("Cinemeta"), 2001)
            },
        )
        assertEquals(TitleInfo(listOf("Cinemeta"), 2001), source(listOf("en")).lookup("movie", "tt1"))
        assertEquals(TitleInfo(listOf("Cinemeta"), 2001), source(listOf("de")).lookup("movie", "tt1"))
        assertEquals(2, cinemetaCalls.get())
    }

    @Test
    fun `the runtime survives the disk cache`() = runBlocking {
        val store = JsonFileStore(File(createTempDirectory("hless-titles").toString(), "titles.json"))
        val tmdbCalls = AtomicInteger(0)
        val src = TitleSource(
            store = store,
            tmdbKey = "k",
            langs = listOf("en"),
            tmdb = { _, _, _, _ ->
                tmdbCalls.incrementAndGet()
                TitleInfo(listOf("A"), 2026, 150)
            },
            cinemeta = { _, _ -> null },
        )
        assertEquals(TitleInfo(listOf("A"), 2026, 150), src.lookup("movie", "tt1"))
        assertEquals(TitleInfo(listOf("A"), 2026, 150), src.lookup("movie", "tt1"))
        assertEquals(1, tmdbCalls.get())
    }
}
