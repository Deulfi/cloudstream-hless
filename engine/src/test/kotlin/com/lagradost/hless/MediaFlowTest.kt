package com.lagradost.hless

import com.lagradost.macos.models.ClearKeyDto
import com.lagradost.macos.models.StreamLinkDto
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaFlowTest {
    private fun link(
        url: String,
        type: String = "M3U8",
        headers: Map<String, String> = emptyMap(),
        drm: ClearKeyDto? = null,
    ) = StreamLinkDto(url = url, name = "n", source = "s", quality = 1080, type = type, headers = headers, drm = drm)

    @Test
    fun `m3u8 wraps into hls manifest`() {
        val url = MediaFlow.wrap("https://mf.example", "pw", link("https://a.example/v.m3u8")).toHttpUrl()
        assertEquals("https://mf.example", url.scheme + "://" + url.host)
        assertEquals("/proxy/hls/manifest.m3u8", url.encodedPath)
        assertEquals("https://a.example/v.m3u8", url.queryParameter("d"))
        assertEquals("pw", url.queryParameter("api_password"))
    }

    @Test
    fun `dash wraps into mpd manifest with clearkey`() {
        val url = MediaFlow.wrap(
            "https://mf.example", "pw", link("https://a.example/v.mpd", type = "DASH", drm = ClearKeyDto(kid = "abc", key = "def")),
        ).toHttpUrl()
        assertEquals("/proxy/mpd/manifest.m3u8", url.encodedPath)
        assertEquals("abc", url.queryParameter("key_id"))
        assertEquals("def", url.queryParameter("key"))
    }

    @Test
    fun `headers become h_ query parameters`() {
        val url = MediaFlow.wrap(
            "https://mf.example", "pw", link("https://a.example/v.mp4", type = "MP4", headers = mapOf("Referer" to "https://site.example/")),
        ).toHttpUrl()
        assertEquals("/proxy/stream", url.encodedPath)
        assertEquals("https://site.example/", url.queryParameter("h_referer"))
    }

    @Test
    fun `wrap without config is null`() {
        assertNull(MediaFlow.wrap(link("https://a.example/v.m3u8")))
    }
}
