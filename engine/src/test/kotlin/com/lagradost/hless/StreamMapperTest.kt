package com.lagradost.hless

import com.lagradost.macos.models.AudioTrackDto
import com.lagradost.macos.models.ClearKeyDto
import com.lagradost.macos.models.StreamLinkDto
import com.lagradost.macos.models.SubtitleDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamMapperTest {
    private fun link(
        url: String,
        quality: Int = 1080,
        type: String = "M3U8",
        headers: Map<String, String> = emptyMap(),
        drm: ClearKeyDto? = null,
        audioTracks: List<AudioTrackDto> = emptyList(),
    ) = StreamLinkDto(
        url = url,
        name = "n",
        source = "s",
        quality = quality,
        type = type,
        headers = headers,
        drm = drm,
        audioTracks = audioTracks,
    )

    private fun map(
        link: StreamLinkDto,
        mediaflow: (StreamLinkDto) -> String? = { null },
        subtitles: List<SubtitleDto> = emptyList(),
        language: String? = null,
    ) = StreamMapper.map("Prov", "provId", link, subtitles, language, mediaflow)

    @Test
    fun `torrents and magnets are dropped`() {
        assertNull(map(link("https://a", type = "TORRENT")))
        assertNull(map(link("https://a", type = "MAGNET")))
    }

    @Test
    fun `non http urls are dropped`() {
        assertNull(map(link("ftp://a")))
        assertNull(map(link("data:text/plain;x")))
    }

    @Test
    fun `split audio tracks are dropped`() {
        assertNull(map(link("https://a", audioTracks = listOf(AudioTrackDto(url = "https://a/aud")))))
    }

    @Test
    fun `drm without mediaflow is dropped`() {
        assertNull(map(link("https://a", type = "DASH", drm = ClearKeyDto(key = "k"))))
    }

    @Test
    fun `mediaflow url is used with binge group`() {
        val stream = map(link("https://a"), mediaflow = { "https://mf.example/proxy/stream?d=x" })
        assertEquals("https://mf.example/proxy/stream?d=x", stream?.url)
        assertEquals("hless|provId|s|1080", stream?.behaviorHints?.bingeGroup)
        assertNull(stream?.behaviorHints?.proxyHeaders)
    }

    @Test
    fun `plain https keeps url with binge group`() {
        val stream = map(link("https://a"))
        assertEquals("https://a", stream?.url)
        assertEquals("hless|provId|s|1080", stream?.behaviorHints?.bingeGroup)
        assertNull(stream?.behaviorHints?.notWebReady)
        assertNull(stream?.behaviorHints?.proxyHeaders)
    }

    @Test
    fun `headers force proxy headers and notWebReady`() {
        val headers = mapOf("Referer" to "https://site.example/")
        val stream = map(link("http://a", headers = headers))
        assertEquals("http://a", stream?.url)
        assertEquals(true, stream?.behaviorHints?.notWebReady)
        assertEquals(headers, stream?.behaviorHints?.proxyHeaders?.request)
    }

    @Test
    fun `subtitles are mapped with indexes`() {
        val subs = listOf(SubtitleDto(url = "https://s1", language = "de"), SubtitleDto(url = "https://s2", language = "en"))
        val stream = map(link("https://a"), subtitles = subs)
        assertEquals(listOf("0", "1"), stream?.subtitles?.map { it.id })
        assertEquals(listOf("https://s1", "https://s2"), stream?.subtitles?.map { it.url })
    }

    @Test
    fun `name carries language and quality`() {
        val stream = map(link("https://a", quality = 0), language = "de")
        assertEquals("CS DE ?", stream?.name)
    }
}
