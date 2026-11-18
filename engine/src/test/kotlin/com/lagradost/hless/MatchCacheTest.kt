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
    fun `miss expires after ten minutes`() {
        val store = JsonFileStore(file, clock = { now })
        val cache = MatchCache(store, clock = { now })
        cache.store("tt1", "p", null)
        assertEquals(MatchCache.Entry.Miss(1_000_000L), cache.lookup("tt1", "p"))
        now += TimeUnit.MINUTES.toMillis(10) + 1
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
    fun `entries nobody can read any more are removed from the file`() {
        val first = JsonFileStore(file, clock = { now }, background = false)
        first.put("old", kotlinx.serialization.json.JsonPrimitive("x"))
        now += TimeUnit.DAYS.toMillis(20)
        first.put("newer", kotlinx.serialization.json.JsonPrimitive("y"))
        first.close()
        now += TimeUnit.DAYS.toMillis(15)       // "old" is 35 days old, "newer" 15
        val second = JsonFileStore(file, clock = { now }, background = false)
        assertEquals(null, second.get("old"))
        assertEquals(true, second.get("newer") != null)
    }

    @Test
    fun `saving does not wait for the disk`() {
        val store = JsonFileStore(file, clock = { now })   // background by default
        store.put("a", kotlinx.serialization.json.JsonPrimitive("x"))
        store.close()
        val reread = JsonFileStore(file, clock = { now })
        assertEquals(true, reread.get("a") != null)
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
