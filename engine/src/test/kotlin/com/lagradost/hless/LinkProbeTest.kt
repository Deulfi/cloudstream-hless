package com.lagradost.hless

import com.lagradost.macos.models.StreamLinkDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinkProbeTest {
    private val masterText = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720\nx.m3u8\n"

    private class Call(val method: String, val range: String?, val headers: Map<String, String>)

    private class FakeHttp(private val pause: Long = 0, private val answer: (Call) -> ProbeResponse?) : ProbeHttp {
        val calls = mutableListOf<Call>()
        override suspend fun fetch(method: String, url: String, headers: Map<String, String>, maxBytes: Int, range: String?): ProbeResponse? {
            val call = Call(method, range, headers)
            synchronized(calls) { calls += call }
            if (pause > 0) delay(pause)
            return answer(call)
        }
    }

    private fun response(status: Int, vararg headers: Pair<String, String>, body: String = "") =
        ProbeResponse(status, headers.toMap(), body.toByteArray())

    private fun hls(url: String = "https://c.example/a.m3u8", headers: Map<String, String> = emptyMap()) =
        StreamLinkDto(url = url, name = "n", source = "s", quality = 400, type = "M3U8", headers = headers)

    private fun file(url: String = "https://c.example/f.mp4") =
        StreamLinkDto(url = url, name = "n", source = "s", quality = 400, type = "VIDEO")

    @Test
    fun `an HLS link is read as a playlist and sends its own headers`() = runBlocking {
        val http = FakeHttp { response(200, body = masterText) }
        val facts = assertNotNull(LinkProbe(http).probe(hls(headers = mapOf("referer" to "https://site.example/"))))
        assertEquals(Kind.MASTER, facts.kind)
        assertEquals(listOf(720), facts.heights)
        assertEquals("GET", http.calls.single().method)
        assertEquals("https://site.example/", http.calls.single().headers["referer"])
    }

    @Test
    fun `a direct file is measured with HEAD only`() = runBlocking {
        val http = FakeHttp { response(200, "content-length" to "1610000000") }
        val facts = assertNotNull(LinkProbe(http).probe(file()))
        assertEquals(Kind.FILE, facts.kind)
        assertEquals(1_610_000_000L, facts.sizeBytes)
        assertEquals(listOf("HEAD"), http.calls.map { it.method })
    }

    @Test
    fun `a host that refuses HEAD is asked for the first byte instead`() = runBlocking {
        val http = FakeHttp { call ->
            if (call.method == "HEAD") response(405) else response(206, "content-range" to "bytes 0-0/963000000")
        }
        val facts = assertNotNull(LinkProbe(http).probe(file()))
        assertEquals(963_000_000L, facts.sizeBytes)
        assertEquals(listOf("HEAD", "GET"), http.calls.map { it.method })
        assertEquals("bytes=0-0", http.calls.last().range)
    }

    @Test
    fun `failures and non-playlists are unknown, never an error`() = runBlocking {
        assertNull(LinkProbe(FakeHttp { null }).probe(hls()))
        assertNull(LinkProbe(FakeHttp { response(403) }).probe(hls()))
        assertNull(LinkProbe(FakeHttp { response(200, body = "<html>blocked</html>") }).probe(hls()))
        assertNull(LinkProbe(FakeHttp { null }).probe(file()))
        assertNull(LinkProbe(FakeHttp { throw IllegalStateException("boom") }).probe(file()))
    }

    @Test
    fun `other link types are not fetched at all`() = runBlocking {
        val http = FakeHttp { response(200, body = masterText) }
        val probe = LinkProbe(http)
        for (type in listOf("DASH", "TORRENT", "MAGNET")) {
            assertNull(probe.probe(StreamLinkDto(url = "https://c.example/x", name = "n", source = "s", quality = 1, type = type)))
        }
        assertEquals(0, http.calls.size)
    }

    @Test
    fun `facts are cached, per url and headers`() = runBlocking {
        val http = FakeHttp { response(200, body = masterText) }
        val probe = LinkProbe(http)
        probe.probe(hls())
        probe.probe(hls())
        assertEquals(1, http.calls.size)
        probe.probe(hls(headers = mapOf("referer" to "x")))
        probe.probe(hls(url = "https://c.example/other.m3u8"))
        assertEquals(3, http.calls.size)
    }

    @Test
    fun `facts live ten minutes and a failure one minute`() = runBlocking {
        val now = AtomicLong(0)
        val answers = AtomicInteger(0)
        val http = FakeHttp { if (answers.incrementAndGet() == 1) null else response(200, body = masterText) }
        val probe = LinkProbe(http, clock = { now.get() })
        assertNull(probe.probe(hls()))
        now.addAndGet(30_000)
        assertNull(probe.probe(hls()))            // still the remembered failure
        assertEquals(1, http.calls.size)
        now.addAndGet(31_000)
        assertNotNull(probe.probe(hls()))         // a minute later it is tried again
        assertEquals(2, http.calls.size)
        now.addAndGet(9 * 60_000)
        probe.probe(hls())
        assertEquals(2, http.calls.size)          // facts still fresh
        now.addAndGet(2 * 60_000)
        probe.probe(hls())
        assertEquals(3, http.calls.size)
    }

    @Test
    fun `the cache is bounded`() = runBlocking {
        val http = FakeHttp { response(200, body = masterText) }
        val probe = LinkProbe(http, maxEntries = 3)
        for (i in 1..5) probe.probe(hls(url = "https://c.example/$i.m3u8"))
        assertEquals(5, http.calls.size)
        probe.probe(hls(url = "https://c.example/5.m3u8"))      // newest is still there
        assertEquals(5, http.calls.size)
        probe.probe(hls(url = "https://c.example/1.m3u8"))      // oldest was evicted
        assertEquals(6, http.calls.size)
    }

    @Test
    fun `a slow host is given up on after the timeout`() = runBlocking {
        val http = FakeHttp(pause = 5_000) { response(200, body = masterText) }
        val started = System.currentTimeMillis()
        assertNull(LinkProbe(http, timeoutMs = 200).probe(hls()))
        assertEquals(true, System.currentTimeMillis() - started < 3_000)
    }
}
