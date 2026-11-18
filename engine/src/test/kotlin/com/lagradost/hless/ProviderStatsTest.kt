package com.lagradost.hless

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderStatsTest {
    private var now = 1_000_000_000L
    private fun stats(dir: File = File(createTempDirectory("hless-pstats").toString())) =
        ProviderStats(JsonFileStore(File(dir, "stats.json"), clock = { now }, background = false), clock = { now })

    @Test
    fun `a provider nobody tried is unknown`() {
        val s = stats()
        assertEquals(Usefulness.UNKNOWN, s.classify("A"))
        assertEquals(0, s.counts("A").tries)
    }

    @Test
    fun `one hit makes a provider likely`() {
        val s = stats()
        s.record("A", SearchOutcome.MISS)
        assertEquals(Usefulness.UNKNOWN, s.classify("A"))
        s.record("A", SearchOutcome.HIT)
        assertEquals(Usefulness.LIKELY, s.classify("A"))
        assertEquals(ProviderStats.Counts(1, 1, 0, now), s.counts("A"))
    }

    @Test
    fun `ten tries without a hit make a provider dormant, failures count as tries`() {
        val s = stats()
        repeat(9) { s.record("A", SearchOutcome.FAIL) }
        assertEquals(Usefulness.UNKNOWN, s.classify("A"))
        s.record("A", SearchOutcome.MISS)
        assertEquals(Usefulness.DORMANT, s.classify("A"))
    }

    @Test
    fun `a hit brings a dormant provider back`() {
        val s = stats()
        repeat(12) { s.record("A", SearchOutcome.MISS) }
        assertEquals(Usefulness.DORMANT, s.classify("A"))
        s.record("A", SearchOutcome.HIT)
        assertEquals(Usefulness.LIKELY, s.classify("A"))
    }

    @Test
    fun `a hit older than thirty days no longer makes a provider likely`() {
        val s = stats()
        s.record("A", SearchOutcome.HIT)
        repeat(9) { s.record("A", SearchOutcome.MISS) }
        assertEquals(Usefulness.LIKELY, s.classify("A"))
        now += TimeUnit.DAYS.toMillis(31) - 1_000
        // the entries themselves are still readable, but the hit is too old
        assertEquals(Usefulness.DORMANT, s.classify("A"))
    }

    @Test
    fun `old behaviour fades but a provider is never forgotten`() {
        val s = stats()
        s.record("A", SearchOutcome.HIT)
        repeat(250) { s.record("A", SearchOutcome.MISS) }
        val c = s.counts("A")
        assertEquals(true, c.tries <= 201)
        assertEquals(true, c.hits >= 1)
        assertEquals(Usefulness.LIKELY, s.classify("A"))
    }

    @Test
    fun `providers are counted separately and survive a restart`() {
        val dir = File(createTempDirectory("hless-pstats").toString())
        val first = JsonFileStore(File(dir, "stats.json"), clock = { now }, background = false)
        ProviderStats(first, clock = { now }).record("A", SearchOutcome.HIT)
        first.close()
        val again = ProviderStats(JsonFileStore(File(dir, "stats.json"), clock = { now }, background = false), clock = { now })
        assertEquals(1, again.counts("A").hits)
        assertEquals(0, again.counts("B").tries)
    }

    @Test
    fun `hosters are counted without regard to case and listed most seen first`() {
        val s = stats()
        s.noteHoster("Voe"); s.noteHoster("DoodStream"); s.noteHoster("doodstream"); s.noteHoster("DOODSTREAM"); s.noteHoster("  ")
        assertEquals(listOf("Voe" to 1).let { listOf("DoodStream" to 3) + it }, s.hosters())
        assertEquals(listOf("DoodStream" to 3), s.hosters(limit = 1))
    }

    @Test
    fun `a very long hoster name is cut`() {
        val s = stats()
        s.noteHoster("x".repeat(100))
        assertEquals(40, s.hosters().single().first.length)
    }
}
