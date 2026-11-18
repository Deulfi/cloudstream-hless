package com.lagradost.hless

import com.lagradost.macos.models.AudioTrackDto
import com.lagradost.macos.models.ClearKeyDto
import com.lagradost.macos.models.StreamLinkDto
import com.lagradost.macos.models.SubtitleDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamMapperTest {
    private val provider = ProviderRef("Prov", "provId", null)

    private fun link(
        url: String,
        quality: Int = 1080,
        type: String = "M3U8",
        headers: Map<String, String> = emptyMap(),
        drm: ClearKeyDto? = null,
        audioTracks: List<AudioTrackDto> = emptyList(),
        name: String = "n",
        source: String = "s",
    ) = StreamLinkDto(
        url = url,
        name = name,
        source = source,
        quality = quality,
        type = type,
        headers = headers,
        drm = drm,
        audioTracks = audioTracks,
    )

    private fun map(
        link: StreamLinkDto,
        facts: LinkFacts? = null,
        mediaflow: (StreamLinkDto) -> String? = { null },
        subtitles: List<SubtitleDto> = emptyList(),
        language: String? = null,
        runtimeSec: Double? = null,
        hostersOff: Set<String> = emptySet(),
    ): MapResult = StreamMapper.map(
        provider.copy(language = language),
        Item(link, facts),
        subtitles,
        MapContext("Title (2026)", runtimeSec, DisplaySettings(), hostersOff, mediaflow),
    )

    private fun stream(result: MapResult): Candidate = (result as MapResult.Stream).candidate

    private fun dropped(result: MapResult): DropReason = (result as MapResult.Dropped).reason

    @Test
    fun `every drop says why`() {
        assertEquals(DropReason.TORRENT, dropped(map(link("https://a", type = "TORRENT"))))
        assertEquals(DropReason.TORRENT, dropped(map(link("https://a", type = "MAGNET"))))
        assertEquals(DropReason.NOT_HTTP, dropped(map(link("ftp://a"))))
        assertEquals(DropReason.NOT_HTTP, dropped(map(link("data:text/plain;x"))))
        assertEquals(
            DropReason.SPLIT_AUDIO,
            dropped(map(link("https://a", audioTracks = listOf(AudioTrackDto(url = "https://a/aud"))))),
        )
        assertEquals(
            DropReason.DRM_NO_MEDIAFLOW,
            dropped(map(link("https://a", type = "DASH", drm = ClearKeyDto(key = "k")))),
        )
    }

    @Test
    fun `a hoster the person switched off is dropped, whatever its case`() {
        val voe = link("https://a", source = "Voe")
        assertEquals(DropReason.HOSTER_OFF, dropped(map(voe, hostersOff = setOf("voe"))))
        assertEquals(DropReason.HOSTER_OFF, dropped(map(link("https://a", source = " VOE "), hostersOff = setOf("voe"))))
        // only that exact hoster: "Voe MP4" is another source name
        stream(map(link("https://a", source = "Voe MP4"), hostersOff = setOf("voe")))
        stream(map(voe, hostersOff = setOf("doodstream")))
    }

    @Test
    fun `mediaflow url is used with a binge group`() {
        val candidate = stream(map(link("https://a"), mediaflow = { "https://mf.example/proxy/stream?d=x" }))
        assertEquals("https://mf.example/proxy/stream?d=x", candidate.stream.url)
        assertEquals("hless|provId|s|1080", candidate.stream.behaviorHints?.bingeGroup)
        assertNull(candidate.stream.behaviorHints?.proxyHeaders)
        // The ranker dedupes by the original link, not by the proxy URL.
        assertEquals("https://a", candidate.url)
    }

    @Test
    fun `plain https keeps the url`() {
        val candidate = stream(map(link("https://a")))
        assertEquals("https://a", candidate.stream.url)
        assertNull(candidate.stream.behaviorHints?.notWebReady)
        assertNull(candidate.stream.behaviorHints?.proxyHeaders)
    }

    @Test
    fun `headers force proxy headers and notWebReady`() {
        val headers = mapOf("Referer" to "https://site.example/")
        val candidate = stream(map(link("http://a", headers = headers)))
        assertEquals("http://a", candidate.stream.url)
        assertEquals(true, candidate.stream.behaviorHints?.notWebReady)
        assertEquals(headers, candidate.stream.behaviorHints?.proxyHeaders?.request)
    }

    @Test
    fun `subtitles are mapped with indexes`() {
        val subs = listOf(SubtitleDto(url = "https://s1", language = "de"), SubtitleDto(url = "https://s2", language = "en"))
        val candidate = stream(map(link("https://a"), subtitles = subs))
        assertEquals(listOf("0", "1"), candidate.stream.subtitles?.map { it.id })
        assertEquals(listOf("https://s1", "https://s2"), candidate.stream.subtitles?.map { it.url })
        assertTrue(candidate.stream.description.endsWith("🔤 DE EN"))
    }

    @Test
    fun `unknown quality is shown as Auto and never as 400p`() {
        for (quality in listOf(400, 0, -1)) {
            val candidate = stream(map(link("https://a", quality = quality, type = "VIDEO")))
            assertEquals("大 Hless\n🎬 Auto\nProv", candidate.stream.name)
            assertTrue("📺 Auto • Direct" in candidate.stream.description)
        }
    }

    @Test
    fun `a probed master lists its variants and its bandwidth`() {
        val facts = LinkFacts(Kind.MASTER, heights = listOf(800, 720, 480), topBandwidthKbps = 2642)
        val candidate = stream(map(link("https://a", quality = 400, source = "Voe"), facts, language = "de"))
        assertEquals("大 Hless\n🎬 800p 🇩🇪\nProv", candidate.stream.name)
        assertEquals(
            "🎬 Title (2026)\n📺 800p · 720p · 480p • HLS\n🔗 Prov • Voe\n💾 ~2.6 Mbps",
            candidate.stream.description,
        )
        assertEquals(800, candidate.quality)
        assertEquals(480, candidate.lowQuality)
    }

    @Test
    fun `a probed file shows size and bitrate and sets videoSize`() {
        val facts = LinkFacts(Kind.FILE, sizeBytes = 2_070_000_000L)
        val candidate = stream(map(link("https://a", type = "VIDEO", source = "DoodStream"), facts, runtimeSec = 9000.0))
        assertTrue("💾 2.07 GB · ~1.8 Mbps" in candidate.stream.description)
        assertEquals(2_070_000_000L, candidate.stream.behaviorHints?.videoSize)
        assertEquals(2_070_000_000L, candidate.sizeBytes)
    }

    @Test
    fun `a file without a known runtime shows the size only`() {
        val facts = LinkFacts(Kind.FILE, sizeBytes = 963_000_000L)
        val candidate = stream(map(link("https://a", type = "VIDEO"), facts, runtimeSec = null))
        assertTrue("💾 963 MB" in candidate.stream.description)
        assertTrue("Mbps" !in candidate.stream.description)
    }

    @Test
    fun `the binge group does not depend on the probe`() {
        val plain = stream(map(link("https://a", quality = 400, source = "Voe")))
        val probed = stream(map(link("https://a", quality = 400, source = "Voe"), LinkFacts(Kind.MASTER, listOf(720))))
        assertEquals(plain.stream.behaviorHints?.bingeGroup, probed.stream.behaviorHints?.bingeGroup)
    }

    @Test
    fun `name carries the language flag`() {
        assertEquals("大 Hless\n🎬 1080p 🇩🇪\nProv", stream(map(link("https://a"), language = "de")).stream.name)
        assertEquals("大 Hless\n🎬 720p 🇬🇧\nProv", stream(map(link("https://a", quality = 720), language = "en")).stream.name)
    }
}
