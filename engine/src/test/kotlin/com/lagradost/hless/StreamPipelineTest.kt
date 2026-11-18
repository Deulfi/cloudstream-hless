package com.lagradost.hless

import com.lagradost.macos.models.StreamLinkDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamPipelineTest {
    private fun link(url: String, quality: Int, source: String, type: String = "M3U8") =
        StreamLinkDto(url = url, name = source, source = source, quality = quality, type = type)

    private fun media(url: String, quality: Int) =
        Item(link(url, quality, "Voe"), LinkFacts(Kind.MEDIA, durationSec = 8700.0))

    private val megakino = ProviderResult(
        ProviderRef("Megakino", "m", "de"),
        listOf(
            media("https://cdn.example/v800.m3u8", 800),
            media("https://cdn.example/v720.m3u8", 720),
            media("https://cdn.example/v480.m3u8", 480),
            Item(link("https://cdn.example/m.m3u8", 400, "Voe"), LinkFacts(Kind.MASTER, listOf(800, 720, 480), 2642)),
            Item(link("https://cdn2.example/f.mp4", 400, "Voe MP4", "VIDEO"), LinkFacts(Kind.FILE, sizeBytes = 1_010_000_000L)),
        ),
        emptyList(),
    )

    private val kellerkino = ProviderResult(
        ProviderRef("Kellerkino", "k", "de"),
        listOf(Item(link("https://dood.example/f.mp4", 1080, "DoodStream", "VIDEO"), LinkFacts(Kind.FILE, sizeBytes = 2_070_000_000L))),
        emptyList(),
    )

    private val title = "Spider-Man: Brand New Day (2026)"

    private fun render(
        results: List<ProviderResult>,
        settings: Settings = Settings(),
        fallbackMinutes: Int? = null,
        mediaflow: (StreamLinkDto) -> String? = { null },
    ) = renderStreams(results, title, fallbackMinutes, settings, mediaflow)

    @Test
    fun `the real shape folds, labels and orders`() {
        val rendered = render(listOf(megakino, kellerkino))
        assertEquals(
            listOf("hless\n🎬 1080p 🇩🇪\nKellerkino", "hless\n🎬 800p 🇩🇪\nMegakino", "hless\n🎬 Auto 🇩🇪\nMegakino"),
            rendered.streams.map { it.name },
        )
        assertEquals(6, rendered.links)
    }

    @Test
    fun `bitrates use the median runtime of the media playlists seen`() {
        val rendered = render(listOf(megakino, kellerkino))
        // 2.07 GB over 8700 s, and 1.01 GB over 8700 s.
        assertTrue("💾 2.07 GB · ~1.9 Mbps" in rendered.streams[0].description, rendered.streams[0].description)
        assertTrue("💾 ~2.6 Mbps" in rendered.streams[1].description, rendered.streams[1].description)
        assertTrue("💾 1.01 GB · ~0.9 Mbps" in rendered.streams[2].description, rendered.streams[2].description)
    }

    @Test
    fun `without a media playlist the title's runtime is used, trailers are ignored`() {
        val withTrailer = kellerkino.copy(
            items = kellerkino.items + Item(link("https://t.example/t.m3u8", 720, "T"), LinkFacts(Kind.MEDIA, durationSec = 150.0)),
        )
        val rendered = render(listOf(withTrailer), fallbackMinutes = 150)
        val dood = rendered.streams.single { "DoodStream" in it.description }
        assertTrue("💾 2.07 GB · ~1.8 Mbps" in dood.description, dood.description)
        assertEquals(null, runtimeSeconds(listOf(kellerkino), null))
        assertEquals(9000.0, runtimeSeconds(listOf(kellerkino), 150))
    }

    @Test
    fun `listing every variant keeps them`() {
        val rendered = render(listOf(megakino, kellerkino), Settings(variants = Variants.ALL))
        assertEquals(6, rendered.streams.size)
    }

    @Test
    fun `what was left out is counted by reason`() {
        val torrent = Item(link("https://t.example/x", 1080, "T", "TORRENT"))
        val split = ProviderResult(ProviderRef("P", "p", null), listOf(torrent), emptyList())
        val rendered = render(listOf(kellerkino, split))
        assertEquals(1, rendered.dropped[DropReason.TORRENT])
        assertEquals(2, rendered.links)
        assertEquals(1, rendered.streams.size)
    }

    @Test
    fun `the proxy switch decides whether links are wrapped`() {
        val wrap: (StreamLinkDto) -> String? = { "https://mf.example/proxy?d=${it.url.length}" }
        assertEquals(
            listOf("https://mf.example/proxy?d=${kellerkino.items[0].link.url.length}"),
            render(listOf(kellerkino), Settings(proxy = true), mediaflow = wrap).streams.map { it.url },
        )
        assertEquals(
            listOf("https://dood.example/f.mp4"),
            render(listOf(kellerkino), Settings(proxy = false), mediaflow = wrap).streams.map { it.url },
        )
    }

    @Test
    fun `settings of one request do not leak into another on the same results`() {
        val results = listOf(megakino, kellerkino)
        val strict = render(results, Settings(rank = RankSettings(minQuality = 1080)))
        val loose = render(results)
        // The master (800p) is below the minimum; the file of unknown quality is kept.
        assertEquals(2, strict.streams.size)
        assertEquals(1, strict.dropped[DropReason.BELOW_MIN_QUALITY])
        assertEquals(3, loose.streams.size)
        assertEquals(null, loose.dropped[DropReason.BELOW_MIN_QUALITY])
    }
}
