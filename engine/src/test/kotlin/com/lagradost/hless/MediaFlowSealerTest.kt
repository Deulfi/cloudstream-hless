package com.lagradost.hless

import com.lagradost.macos.models.ClearKeyDto
import com.lagradost.macos.models.StreamLinkDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaFlowSealerTest {
    private val base = "https://mf.example"

    private fun link(url: String, type: String = "VIDEO", headers: Map<String, String> = emptyMap(), drm: ClearKeyDto? = null) =
        StreamLinkDto(url = url, name = "n", source = "s", quality = 1080, type = type, headers = headers, drm = drm)

    private fun stream(url: String) = StremioStream(name = "n", description = "d", url = url, behaviorHints = StreamHints(bingeGroup = "g"))

    @Test
    fun `a plain url becomes the sealed one, with headers and extra parameters passed on`() = runBlocking {
        val sent = ArrayList<Pair<String, String>>()
        val sealer = MediaFlowSealer(base, "pw", shared = false, post = { url, json ->
            sent += url to json
            """{"url":"$base/_token_abc/proxy/mpd/manifest.m3u8"}"""
        })
        val plain = MediaFlow.wrap(base, "pw", link("https://cdn.example/a.mpd", type = "DASH", headers = mapOf("Referer" to "https://site.example/"), drm = ClearKeyDto(kid = "k1", key = "v1")))
        val out = sealer.sealAll(listOf(stream(plain)))
        assertEquals("$base/_token_abc/proxy/mpd/manifest.m3u8", out.single().url)
        assertEquals("g", out.single().behaviorHints?.bingeGroup)
        val (url, json) = sent.single()
        assertEquals("$base/generate_url", url)
        val body = Json.parseToJsonElement(json).jsonObject
        assertEquals("/proxy/mpd/manifest.m3u8", body["endpoint"]!!.jsonPrimitive.content)
        assertEquals("https://cdn.example/a.mpd", body["destination_url"]!!.jsonPrimitive.content)
        assertEquals("https://site.example/", body["request_headers"]!!.jsonObject["referer"]!!.jsonPrimitive.content)
        assertEquals("v1", body["query_params"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertNull(body["query_params"]!!.jsonObject["api_password"])
    }

    @Test
    fun `a sealed url is reused instead of asked for again`() = runBlocking {
        var calls = 0
        val sealer = MediaFlowSealer(base, "pw", shared = false, post = { _, _ -> calls++; """{"url":"$base/_token_x/proxy/stream"}""" })
        val plain = MediaFlow.wrap(base, "pw", link("https://cdn.example/a.mp4"))
        sealer.sealAll(listOf(stream(plain)))
        sealer.sealAll(listOf(stream(plain)))
        assertEquals(1, calls)
    }

    @Test
    fun `other urls are left alone`() = runBlocking {
        val sealer = MediaFlowSealer(base, "pw", shared = true, post = { _, _ -> error("not asked") })
        val direct = stream("https://cdn.example/a.mp4")
        assertEquals(listOf(direct), sealer.sealAll(listOf(direct)))
    }

    @Test
    fun `when sealing fails a private server keeps the plain url`() = runBlocking {
        val sealer = MediaFlowSealer(base, "pw", shared = false, post = { _, _ -> null })
        val plain = MediaFlow.wrap(base, "pw", link("https://cdn.example/a.mp4"))
        assertEquals(plain, sealer.sealAll(listOf(stream(plain))).single().url)
    }

    @Test
    fun `when sealing fails a shared server never hands out the password`() = runBlocking {
        val sealer = MediaFlowSealer(base, "pw", shared = true, post = { _, _ -> """{"error":"nope"}""" })
        val withHeaders = MediaFlow.wrap(base, "pw", link("https://cdn.example/a.mp4", headers = mapOf("Referer" to "https://site.example/")))
        val drm = MediaFlow.wrap(base, "pw", link("https://cdn.example/a.mpd", type = "DASH", drm = ClearKeyDto(kid = "k", key = "v")))
        val out = sealer.sealAll(listOf(stream(withHeaders), stream(drm)))
        // the DRM stream cannot play without the proxy and is left out
        val only = out.single()
        assertEquals("https://cdn.example/a.mp4", only.url)
        assertEquals(true, only.behaviorHints?.notWebReady)
        assertEquals(mapOf("referer" to "https://site.example/"), only.behaviorHints?.proxyHeaders?.request)
        assertTrue(out.none { it.url.orEmpty().contains("pw") })
    }
}
