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
            order = listOf("B", "A"), smart = false, hostOff = listOf("Voe"), hint = false,
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
        assertNull(decodePrefs("A".repeat(8_001)))
        // Short as text, but more than 6 KiB once decoded.
        assertNull(decodePrefs(segment("""{"off":["${"a".repeat(6_200)}"]}""")))
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
        val many = (1..300).map { "p$it" }
        val rank = Prefs(off = many, first = many).resolve(defaults).rank
        assertEquals(1 + 200, rank.providersDeny.size)
        assertEquals(60, rank.providersFirst.size)
        assertEquals(40, Prefs(first = listOf("x".repeat(100))).resolve(defaults).rank.providersFirst.single().length)
    }

    @Test
    fun `provider order, smart ordering, hosters and the note are read and bounded`() {
        val settings = Prefs(order = listOf("B", "A", " "), smart = false, hostOff = listOf("Voe", "DoodStream"), hint = false).resolve(defaults)
        assertEquals(listOf("B", "A"), settings.rank.order)
        assertEquals(false, settings.rank.smart)
        assertEquals(listOf("Voe", "DoodStream"), settings.rank.hostersOff)
        assertEquals(false, settings.hint)
        val many = (1..300).map { "p$it" }
        val bounded = Prefs(order = many, hostOff = many).resolve(defaults).rank
        assertEquals(100, bounded.order.size)
        assertEquals(100, bounded.hostersOff.size)
        // not given: the server's values stay
        assertEquals(true, Prefs().resolve(defaults).rank.smart)
        assertEquals(true, Prefs().resolve(defaults).hint)
    }

    @Test
    fun `a list of providers to keep replaces a long list of providers to drop`() {
        val rank = Prefs(only = listOf("A", "B"), off = listOf("B")).resolve(defaults).rank
        assertEquals(listOf("A", "B"), rank.providersAllow)
        assertEquals(true, askProvider("A", rank))
        assertEquals(false, askProvider("B", rank))      // off still applies
        assertEquals(false, askProvider("C", rank))
        // the server's own allow list can only be narrowed
        val server = defaults.copy(rank = defaults.rank.copy(providersAllow = listOf("A", "Z")))
        assertEquals(listOf("A"), Prefs(only = listOf("a", "Q")).resolve(server).rank.providersAllow)
        assertEquals(listOf("A", "Z"), Prefs(only = listOf("Q")).resolve(server).rank.providersAllow)
    }

    @Test
    fun `ninety providers with seventy off fit in the link either way round`() {
        val all = (1..90).map { "Provider$it" }
        val keep = all.take(20)
        val link = encodePrefs(Prefs(only = keep, order = all, first = all.take(3)))
        assertEquals(true, link.length < 3_000)
        val back = decodePrefs(link)!!.resolve(defaults).rank
        assertEquals(keep, back.providersAllow)
    }

    @Test
    fun `a person's own proxy is read when the address is plain and the password usable`() {
        val own = Prefs(mfUrl = " https://mf.example.org/ ", mfPw = "secret").resolve(defaults).ownProxy
        assertEquals(OwnProxy("https://mf.example.org", "secret"), own)
        for (bad in listOf("ftp://mf.example", "https://user:pw@mf.example", "https://mf.example/?x=1", "mf.example", "https://a b.example", "http://" + "a".repeat(300))) {
            assertNull(Prefs(mfUrl = bad, mfPw = "secret").resolve(defaults).ownProxy, bad)
        }
        assertNull(Prefs(mfUrl = "https://mf.example.org", mfPw = "").resolve(defaults).ownProxy)
        assertNull(Prefs(mfUrl = "https://mf.example.org", mfPw = "a b").resolve(defaults).ownProxy)
        assertNull(Prefs(mfUrl = "https://mf.example.org").resolve(defaults).ownProxy)
    }
}
