package com.lagradost.hless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamRankerTest {
    private fun candidate(
        name: String,
        provider: String,
        language: String?,
        quality: Int,
        url: String,
        low: Int = quality,
        size: Long? = null,
    ) = Candidate(StremioStream(name = name, description = "", url = url), provider, language, quality, url, low, size)

    private fun urls(candidates: List<Candidate>, settings: RankSettings) =
        rank(candidates, settings).streams.map { it.url }

    @Test
    fun `defaults are six per provider and forty in total`() {
        assertEquals(6, RankSettings().maxPerProvider)
        assertEquals(40, RankSettings().maxStreams)
    }

    @Test
    fun `preferred language provider beats other`() {
        val a = candidate("P1", "A", "en", 1080, "https://a")
        val b = candidate("P2", "B", "de", 720, "https://b")
        assertEquals(listOf("https://b", "https://a"), urls(listOf(b, a), RankSettings(langs = listOf("de", "en"))))
    }

    @Test
    fun `a region suffix does not hide the language`() {
        val a = candidate("P1", "A", "en", 1080, "https://a")
        val b = candidate("P2", "B", "pt-BR", 720, "https://b")
        assertEquals(listOf("https://b", "https://a"), urls(listOf(a, b), RankSettings(langs = listOf("pt"))))
    }

    @Test
    fun `provider first list comes first`() {
        val a = candidate("P1", "A", "en", 1080, "https://a")
        val b = candidate("P2", "B", "en", 720, "https://b")
        assertEquals(listOf("https://b", "https://a"), urls(listOf(a, b), RankSettings(providersFirst = listOf("B"))))
    }

    @Test
    fun `min quality drops below and keeps unknown`() {
        val a = candidate("P1", "A", "en", 720, "https://a")
        val b = candidate("P2", "B", "en", 480, "https://b")
        val c = candidate("P3", "C", "en", 0, "https://c")
        val ranked = rank(listOf(a, b, c), RankSettings(minQuality = 720))
        assertEquals(listOf("https://a", "https://c"), ranked.streams.map { it.url })
        assertEquals(1, ranked.dropped[DropReason.BELOW_MIN_QUALITY])
    }

    @Test
    fun `the unknown placeholder 400 sorts after a real 360`() {
        val unknown = candidate("U", "A", "en", 400, "https://u")
        val real = candidate("R", "B", "en", 360, "https://r")
        assertEquals(listOf("https://r", "https://u"), urls(listOf(unknown, real), RankSettings()))
    }

    @Test
    fun `the unknown placeholder 400 is kept by a minimum`() {
        val unknown = candidate("U", "A", "en", 400, "https://u")
        assertEquals(listOf("https://u"), urls(listOf(unknown), RankSettings(minQuality = 720)))
    }

    @Test
    fun `max quality hides what is above, but keeps a master that has a lower variant`() {
        val big = candidate("B", "A", "en", 2160, "https://big")
        val master = candidate("M", "B", "en", 2160, "https://master", low = 720)
        val small = candidate("S", "C", "en", 720, "https://small")
        val unknown = candidate("U", "D", "en", 0, "https://unknown")
        val ranked = rank(listOf(big, master, small, unknown), RankSettings(maxQuality = 1080))
        assertEquals(listOf("https://master", "https://small", "https://unknown"), ranked.streams.map { it.url })
        assertEquals(1, ranked.dropped[DropReason.ABOVE_MAX_QUALITY])
    }

    @Test
    fun `per provider cap keeps the best per provider`() {
        val high = candidate("H", "A", "en", 1080, "https://h")
        val mid = candidate("M", "A", "en", 720, "https://m")
        val low = candidate("L", "A", "en", 540, "https://l")
        val ranked = rank(listOf(low, high, mid), RankSettings(maxPerProvider = 2))
        assertEquals(listOf("https://h", "https://m"), ranked.streams.map { it.url })
        assertEquals(1, ranked.dropped[DropReason.OVER_CAP])
    }

    @Test
    fun `a provider with six entries keeps six and one with eight keeps six`() {
        val six = (1..6).map { candidate("n$it", "A", "en", 100 * it, "https://a/$it") }
        val eight = (1..8).map { candidate("n$it", "B", "en", 100 * it, "https://b/$it") }
        assertEquals(6, urls(six, RankSettings()).size)
        assertEquals(6, urls(eight, RankSettings()).size)
    }

    @Test
    fun `duplicate urls are collapsed and counted`() {
        val a = candidate("P1", "A", "en", 1080, "https://x")
        val b = candidate("P2", "B", "en", 720, "https://x")
        val ranked = rank(listOf(a, b), RankSettings())
        assertEquals(listOf("https://x"), ranked.streams.map { it.url })
        assertEquals(1, ranked.dropped[DropReason.DUPLICATE])
    }

    @Test
    fun `max streams cuts the tail`() {
        val a = candidate("P1", "A", "en", 1080, "https://a")
        val b = candidate("P2", "B", "en", 720, "https://b")
        val c = candidate("P3", "C", "en", 540, "https://c")
        assertEquals(listOf("https://a", "https://b"), urls(listOf(a, b, c), RankSettings(maxStreams = 2)))
    }

    @Test
    fun `sort modes put their key first`() {
        val deSmall = candidate("1", "Zed", "de", 480, "https://1", size = 100)
        val enBig = candidate("2", "Alpha", "en", 1080, "https://2", size = 500)
        val enMid = candidate("3", "Mid", "en", 720, "https://3", size = 900)
        val all = listOf(deSmall, enBig, enMid)
        val langs = listOf("de", "en")
        assertEquals(
            listOf("https://1", "https://2", "https://3"),
            urls(all, RankSettings(langs = langs, sort = SortMode.LANG)),
        )
        assertEquals(
            listOf("https://2", "https://3", "https://1"),
            urls(all, RankSettings(langs = langs, sort = SortMode.QUALITY)),
        )
        assertEquals(
            listOf("https://2", "https://3", "https://1"),
            urls(all, RankSettings(langs = langs, sort = SortMode.PROVIDER)),
        )
        assertEquals(
            listOf("https://3", "https://2", "https://1"),
            urls(all, RankSettings(langs = langs, sort = SortMode.SIZE)),
        )
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
