package com.lagradost.hless

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchCacheTest {
    private var now = 1_000_000L
    private val file = File(createTempDirectory("hless-match-cache").toString(), "matches.json")

    @Test
    fun `stored url reads back as a hit`() {
        val store = JsonFileStore(file, clock = { now })
        val cache = MatchCache(store, clock = { now })
        cache.store("tt1", "p", "https://example.org/page")
        assertEquals(MatchCache.Entry.Hit("https://example.org/page", 1_000_000L), cache.lookup("tt1", "p"))
    }

    @Test
    fun `hit expires after thirty days`() {
        val store = JsonFileStore(file, clock = { now })
        val cache = MatchCache(store, clock = { now })
        cache.store("tt1", "p", "https://example.org/page")
        now += TimeUnit.DAYS.toMillis(31)
        assertEquals(MatchCache.Entry.Absent, cache.lookup("tt1", "p"))
    }

    @Test
    fun `miss expires after one hour`() {
        val store = JsonFileStore(file, clock = { now })
        val cache = MatchCache(store, clock = { now })
        cache.store("tt1", "p", null)
        assertEquals(MatchCache.Entry.Miss(1_000_000L), cache.lookup("tt1", "p"))
        now += TimeUnit.HOURS.toMillis(1) + 1
        assertEquals(MatchCache.Entry.Absent, cache.lookup("tt1", "p"))
    }

    @Test
    fun `forget removes the entry`() {
        val store = JsonFileStore(file, clock = { now })
        val cache = MatchCache(store, clock = { now })
        cache.store("tt1", "p", "https://example.org/page")
        cache.forget("tt1", "p")
        assertEquals(MatchCache.Entry.Absent, cache.lookup("tt1", "p"))
    }

    @Test
    fun `entries survive a restart`() {
        val first = JsonFileStore(file, clock = { now })
        MatchCache(first, clock = { now }).store("tt1", "p", "https://example.org/page")
        first.close()
        val second = JsonFileStore(file, clock = { now })
        assertEquals(
            MatchCache.Entry.Hit("https://example.org/page", 1_000_000L),
            MatchCache(second, clock = { now }).lookup("tt1", "p"),
        )
    }
}
