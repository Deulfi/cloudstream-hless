package com.lagradost.hless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamRankerTest {
    private fun candidate(name: String, provider: String, language: String?, quality: Int, url: String) =
        Candidate(StremioStream(name = name, description = "", url = url), provider, language, quality, url)

    @Test
    fun `preferred language provider beats other`() {
        val a = candidate("P1", "A", "en", 1080, "https://a")
        val b = candidate("P2", "B", "de", 720, "https://b")
        val ranked = rank(listOf(b, a), RankSettings(langs = listOf("de", "en")))
        assertEquals(listOf("https://b", "https://a"), ranked.map { it.url })
    }

    @Test
    fun `provider first list comes first`() {
        val a = candidate("P1", "A", "en", 1080, "https://a")
        val b = candidate("P2", "B", "en", 720, "https://b")
        val ranked = rank(listOf(a, b), RankSettings(providersFirst = listOf("B")))
        assertEquals(listOf("https://b", "https://a"), ranked.map { it.url })
    }

    @Test
    fun `min quality drops below and keeps unknown`() {
        val a = candidate("P1", "A", "en", 720, "https://a")
        val b = candidate("P2", "B", "en", 480, "https://b")
        val c = candidate("P3", "C", "en", 0, "https://c")
        val ranked = rank(listOf(a, b, c), RankSettings(minQuality = 720))
        assertEquals(listOf("https://a", "https://c"), ranked.map { it.url })
    }

    @Test
    fun `per provider cap keeps the best per provider`() {
        val high = candidate("H", "A", "en", 1080, "https://h")
        val mid = candidate("M", "A", "en", 720, "https://m")
        val low = candidate("L", "A", "en", 540, "https://l")
        val ranked = rank(listOf(low, high, mid), RankSettings(maxPerProvider = 2))
        assertEquals(listOf("https://h", "https://m"), ranked.map { it.url })
    }

    @Test
    fun `duplicate urls are collapsed`() {
        val a = candidate("P1", "A", "en", 1080, "https://x")
        val b = candidate("P2", "B", "en", 720, "https://x")
        val ranked = rank(listOf(a, b), RankSettings())
        assertEquals(listOf("https://x"), ranked.map { it.url })
    }

    @Test
    fun `max streams cuts the tail`() {
        val a = candidate("P1", "A", "en", 1080, "https://a")
        val b = candidate("P2", "B", "en", 720, "https://b")
        val c = candidate("P3", "C", "en", 540, "https://c")
        val ranked = rank(listOf(a, b, c), RankSettings(maxStreams = 2))
        assertEquals(listOf("https://a", "https://b"), ranked.map { it.url })
    }

    @Test
    fun `ask provider deny wins over first`() {
        assertFalse(askProvider("P", RankSettings(providersDeny = listOf("P"), providersFirst = listOf("P"))))
    }

    @Test
    fun `ask provider allow list only allows listed`() {
        val settings = RankSettings(providersAllow = listOf("Q"))
        assertFalse(askProvider("P", settings))
        assertTrue(askProvider("Q", settings))
    }
}
