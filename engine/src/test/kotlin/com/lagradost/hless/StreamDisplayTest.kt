package com.lagradost.hless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamDisplayTest {
    private fun data(
        heights: List<Int>,
        type: String,
        provider: String,
        source: String,
        language: String? = "de",
        size: Long? = null,
        kbps: Int? = null,
        subs: List<String> = emptyList(),
        title: String = "Spider-Man: Brand New Day (2026)",
    ) = DisplayData(title, heights, type, provider, source, language, size, kbps, subs)

    private val display = DisplaySettings()

    @Test
    fun `knownHeight treats 0, negatives and the 400 placeholder as unknown`() {
        assertNull(knownHeight(0))
        assertNull(knownHeight(-1))
        assertNull(knownHeight(400))
        assertEquals(360, knownHeight(360))
        assertEquals(800, knownHeight(800))
        assertEquals(1080, knownHeight(1080))
    }

    @Test
    fun `golden example, a direct file`() {
        val d = data(listOf(1080), "VIDEO", "Kellerkino", "DoodStream", size = 2_070_000_000L, kbps = 1840)
        assertEquals("hless\n🎬 1080p 🇩🇪\nKellerkino", renderName(d, display))
        assertEquals(
            "🎬 Spider-Man: Brand New Day (2026)\n📺 1080p • Direct\n🔗 Kellerkino • DoodStream\n💾 2.07 GB · ~1.8 Mbps",
            renderDescription(d, display),
        )
    }

    @Test
    fun `golden example, an HLS master with three variants`() {
        val d = data(listOf(800, 720, 480), "M3U8", "Megakino", "Voe", kbps = 2642)
        assertEquals("hless\n🎬 800p 🇩🇪\nMegakino", renderName(d, display))
        assertEquals(
            "🎬 Spider-Man: Brand New Day (2026)\n📺 800p · 720p · 480p • HLS\n🔗 Megakino • Voe\n💾 ~2.6 Mbps",
            renderDescription(d, display),
        )
    }

    @Test
    fun `golden example, unknown quality and a source equal to the provider`() {
        val d = data(emptyList(), "VIDEO", "FilmFrei24", "FilmFrei24", size = 7_900_000_000L, kbps = 7022)
        assertEquals("hless\n🎬 Auto 🇩🇪\nFilmFrei24", renderName(d, display))
        assertEquals(
            "🎬 Spider-Man: Brand New Day (2026)\n📺 Auto • Direct\n🔗 FilmFrei24\n💾 7.90 GB · ~7.0 Mbps",
            renderDescription(d, display),
        )
    }

    @Test
    fun `flag modes and icons`() {
        val d = data(listOf(1080), "VIDEO", "Kellerkino", "DoodStream")
        assertEquals("hless\n🎬 1080p DE\nKellerkino", renderName(d, DisplaySettings(flags = FlagMode.CODE)))
        assertEquals("hless\n🎬 1080p\nKellerkino", renderName(d, DisplaySettings(flags = FlagMode.NONE)))
        assertEquals("mine\n1080p 🇩🇪\nKellerkino", renderName(d, DisplaySettings(label = "mine", icons = false)))
        assertEquals(
            "Spider-Man: Brand New Day (2026)\n1080p • Direct\nKellerkino • DoodStream",
            renderDescription(d, DisplaySettings(icons = false)),
        )
    }

    @Test
    fun `an unknown language is shown as letters, never as a guessed flag`() {
        assertEquals("🇯🇵", languageLabel("ja", FlagMode.EMOJI))
        assertEquals("🇰🇷", languageLabel("ko", FlagMode.EMOJI))
        assertEquals("🇵🇹", languageLabel("pt-BR", FlagMode.EMOJI))
        assertEquals("XX", languageLabel("xx", FlagMode.EMOJI))
        assertNull(languageLabel("multi", FlagMode.EMOJI))
        assertNull(languageLabel(null, FlagMode.EMOJI))
        assertNull(languageLabel("de", FlagMode.NONE))
    }

    @Test
    fun `long titles and provider names are cut`() {
        val d = data(listOf(720), "M3U8", "A very long provider name", "x", title = "t".repeat(60))
        assertEquals(40, renderDescription(d, display).lines().first().removePrefix("🎬 ").length)
        assertTrue(renderDescription(d, display).lines().first().endsWith("…"))
        assertEquals("A very long p…", renderName(d, display).lines().last())
    }

    private fun wellFormed(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                return false
            } else {
                i++
            }
        }
        return true
    }

    @Test
    fun `truncate never splits a surrogate pair`() {
        val text = "ab" + "😀".repeat(5)
        assertEquals("ab😀…", truncate(text, 5))
        assertEquals("ab…", truncate(text, 4))
        for (max in 2..text.length) assertTrue(wellFormed(truncate(text, max)), "max=$max")
        assertEquals("short", truncate("short", 5))
    }

    @Test
    fun `subtitle languages are distinct upper-case codes, at most five`() {
        val d = data(listOf(720), "M3U8", "P", "s", subs = listOf("de", "EN", "de", "fr", "es", "it", "pt", "ja"))
        assertTrue(renderDescription(d, display).endsWith("🔤 DE EN FR ES IT"))
    }

    @Test
    fun `sizes and bitrates are formatted with a dot and decimal units`() {
        assertEquals("963 MB", formatSize(963_000_000L))
        assertEquals("1.01 GB", formatSize(1_010_000_000L))
        assertEquals("~0.9 Mbps", formatMbps(929))
    }
}
