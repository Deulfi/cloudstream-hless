package com.lagradost.hless

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrefsTest {
    private val defaults = Settings(
        rank = RankSettings(
            langs = listOf("en"),
            providersDeny = listOf("Server"),
            providersFirst = listOf("X"),
            minQuality = 480,
        ),
    )

    private fun segment(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun `preferences survive a round trip`() {
        val prefs = Prefs(
            langs = listOf("de", "en"), minH = 720, maxH = 1080, off = listOf("A"), first = listOf("B"),
            sort = "size", perProv = 3, total = 20, variants = "all", flags = "code", icons = false, proxy = false,
        )
        assertEquals(prefs, decodePrefs(encodePrefs(prefs)))
    }

    @Test
    fun `only what differs is written`() {
        assertEquals("""{"minH":720}""", String(Base64.getUrlDecoder().decode(encodePrefs(Prefs(minH = 720)))))
    }

    @Test
    fun `anything wrong with the segment gives the defaults, never an error`() {
        assertNull(decodePrefs(null))
        assertNull(decodePrefs(""))
        assertNull(decodePrefs("!!!not base64"))
        assertNull(decodePrefs(segment("not json")))
        assertNull(decodePrefs(segment("""{"minH":"abc"}""")))
        assertNull(decodePrefs(segment("""{"langs":"de"}""")))
        assertNull(decodePrefs(segment("""[1,2,3]""")))
        assertNull(decodePrefs("A".repeat(3_001)))
        // Short as text, but more than 2 KiB once decoded.
        assertNull(decodePrefs(segment("""{"off":["${"a".repeat(2_100)}"]}""")))
    }

    @Test
    fun `unknown keys are ignored`() {
        assertEquals(Prefs(minH = 720), decodePrefs(segment("""{"unknown":1,"password":"x","minH":720}""")))
    }

    @Test
    fun `no preferences means the defaults`() {
        assertEquals(defaults, (null as Prefs?).resolve(defaults))
        assertEquals(defaults, Prefs().resolve(defaults))
    }

    @Test
    fun `values are clamped`() {
        val rank = Prefs(minH = 99_999, perProv = 0, total = 500).resolve(defaults).rank
        assertEquals(4_320, rank.minQuality)
        assertEquals(1, rank.maxPerProvider)
        assertEquals(100, rank.maxStreams)
        assertEquals(20, Prefs(perProv = 99).resolve(defaults).rank.maxPerProvider)
        assertEquals(0, Prefs(minH = -5).resolve(defaults).rank.minQuality)
    }

    @Test
    fun `a maximum below the minimum is dropped`() {
        val rank = Prefs(minH = 1080, maxH = 720).resolve(defaults).rank
        assertEquals(1080, rank.minQuality)
        assertEquals(0, rank.maxQuality)
        assertEquals(1080, Prefs(maxH = 1080).resolve(defaults).rank.maxQuality)
    }

    @Test
    fun `a person can add to the server's deny list but not remove from it`() {
        val rank = Prefs(off = listOf("Bad", "Server")).resolve(defaults).rank
        assertEquals(listOf("Server", "Bad"), rank.providersDeny)
        assertEquals(listOf("Server"), Prefs(off = emptyList()).resolve(defaults).rank.providersDeny)
    }

    @Test
    fun `the first list and the languages are replaced, and languages are normalised`() {
        val rank = Prefs(first = listOf("B", "A"), langs = listOf("DE", "pt-BR", "x", "toolong", "de")).resolve(defaults).rank
        assertEquals(listOf("B", "A"), rank.providersFirst)
        assertEquals(listOf("de", "pt"), rank.langs)
        assertEquals(listOf("en"), Prefs(langs = listOf("x", "123")).resolve(defaults).rank.langs)
        assertEquals(listOf("en"), Prefs(langs = emptyList()).resolve(defaults).rank.langs)
    }

    @Test
    fun `appearance and sorting options are read, bad values are ignored`() {
        val settings = Prefs(sort = "size", variants = "all", flags = "none", icons = false, proxy = false).resolve(defaults)
        assertEquals(SortMode.SIZE, settings.rank.sort)
        assertEquals(Variants.ALL, settings.variants)
        assertEquals(FlagMode.NONE, settings.display.flags)
        assertEquals(false, settings.display.icons)
        assertEquals(false, settings.proxy)
        val ignored = Prefs(sort = "random", variants = "many", flags = "banana").resolve(defaults)
        assertEquals(defaults, ignored)
    }

    @Test
    fun `lists are bounded`() {
        val many = (1..100).map { "p$it" }
        val rank = Prefs(off = many, first = many).resolve(defaults).rank
        assertEquals(1 + 60, rank.providersDeny.size)
        assertEquals(60, rank.providersFirst.size)
        assertEquals(40, Prefs(first = listOf("x".repeat(100))).resolve(defaults).rank.providersFirst.single().length)
    }
}
