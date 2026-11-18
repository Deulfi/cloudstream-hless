package com.lagradost.hless

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeFetchTest {
    private fun address(text: String): InetAddress = InetAddress.getByName(text)   // literals only: no DNS

    @Test
    fun `private, loopback, link-local and reserved addresses are not public`() {
        for (text in listOf(
            "127.0.0.1", "127.1.2.3", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.1.1",
            "0.0.0.0", "100.64.0.1", "100.127.255.254", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::1", "fd00::1", "fc00::1", "fe80::1", "::",
        )) {
            assertFalse(isPublicAddress(address(text)), text)
        }
    }

    @Test
    fun `public addresses are public`() {
        for (text in listOf("93.184.216.34", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:4700::1111")) {
            assertTrue(isPublicAddress(address(text)), text)
        }
    }

    @Test
    fun `the resolver refuses a host if any of its addresses is not public`() {
        val mixed = Dns { listOf(address("93.184.216.34"), address("10.0.0.5")) }
        assertFailsWith<UnknownHostException> { PublicOnlyDns(mixed).lookup("x.example") }
        val public = Dns { listOf(address("93.184.216.34")) }
        assertEquals(1, PublicOnlyDns(public).lookup("x.example").size)
        val privateOnly = Dns { listOf(address("10.0.0.5")) }
        assertFailsWith<UnknownHostException> { PublicOnlyDns(privateOnly).lookup("x.example") }
        assertEquals(1, PublicOnlyDns(privateOnly, allowPrivate = true).lookup("x.example").size)
    }

    @Test
    fun `ip literals are recognised`() {
        assertTrue(isIpLiteral("10.0.0.1"))
        assertTrue(isIpLiteral("::1"))
        assertFalse(isIpLiteral("cdn.example"))
        assertFalse(isIpLiteral("10.0.0.1.example"))
    }

    @Test
    fun `only safe request headers are replayed`() {
        val kept = sanitizeProbeHeaders(
            mapOf(
                "User-Agent" to "UA", "referer" to "https://site.example/", "Origin" to "https://site.example",
                "Host" to "evil", "Content-Length" to "5", "Transfer-Encoding" to "chunked", "Connection" to "close",
                "Range" to "bytes=5-", "Proxy-Authorization" to "x", "Upgrade" to "websocket", "TE" to "trailers",
                "X-Bad" to "a\r\nInjected: 1", "Bad Name" to "v", "X-Wide" to "ü", "" to "v",
            ),
        )
        assertEquals(setOf("User-Agent", "referer", "Origin"), kept.keys)
    }

    // ---- a real server on the loopback interface; only reachable with allowPrivate = true.

    private val servers = mutableListOf<HttpServer>()

    @AfterTest
    fun stop() {
        servers.forEach { it.stop(0) }
    }

    private fun server(configure: (HttpServer) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        configure(server)
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}"
    }

    @Test
    fun `a request to a loopback address is refused unless private addresses are allowed`() = runBlocking {
        val base = server { s ->
            s.createContext("/p") { ex ->
                val body = "#EXTM3U\n".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        assertNull(SafeFetch().fetch("GET", "$base/p", emptyMap(), 1024))
        val response = assertNotNull(SafeFetch(allowPrivate = true).fetch("GET", "$base/p", emptyMap(), 1024))
        assertEquals(200, response.status)
        assertEquals("#EXTM3U\n", String(response.body))
    }

    @Test
    fun `redirects are followed by hand, at most three`() = runBlocking {
        val base = server { s ->
            s.createContext("/final") { ex ->
                ex.sendResponseHeaders(200, 2)
                ex.responseBody.use { it.write("ok".toByteArray()) }
            }
            for (i in 1..5) {
                s.createContext("/r$i") { ex ->
                    ex.responseHeaders.add("Location", if (i == 5) "/final" else "/r${i + 1}")
                    ex.sendResponseHeaders(302, -1)
                    ex.close()
                }
            }
        }
        val fetch = SafeFetch(allowPrivate = true)
        assertEquals("ok", String(assertNotNull(fetch.fetch("GET", "$base/r4", emptyMap(), 16)).body))   // r4 -> r5 -> final
        assertNull(fetch.fetch("GET", "$base/r1", emptyMap(), 16))                                      // too many hops
    }

    @Test
    fun `the body is bounded, the headers are lower-cased and the link headers are sent`() = runBlocking {
        var seenReferer: String? = null
        var seenRange: String? = null
        val base = server { s ->
            s.createContext("/big") { ex ->
                seenReferer = ex.requestHeaders.getFirst("Referer")
                seenRange = ex.requestHeaders.getFirst("Range")
                val body = ByteArray(10_000) { 'x'.code.toByte() }
                ex.responseHeaders.add("X-Thing", "v")
                ex.sendResponseHeaders(206, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        val response = assertNotNull(
            SafeFetch(allowPrivate = true).fetch("GET", "$base/big", mapOf("Referer" to "https://site.example/", "Range" to "bytes=9-"), 100, range = "bytes=0-0"),
        )
        assertEquals(100, response.body.size)
        assertEquals("v", response.headers["x-thing"])
        assertEquals("https://site.example/", seenReferer)
        // The probe's own range replaces any range from the link.
        assertEquals("bytes=0-0", seenRange)
    }

    @Test
    fun `only http and https are fetched`() = runBlocking {
        assertNull(SafeFetch(allowPrivate = true).fetch("GET", "file:///etc/passwd", emptyMap(), 16))
        assertNull(SafeFetch(allowPrivate = true).fetch("GET", "ftp://example.org/x", emptyMap(), 16))
        assertNull(SafeFetch().fetch("GET", "not a url", emptyMap(), 16))
    }
}
