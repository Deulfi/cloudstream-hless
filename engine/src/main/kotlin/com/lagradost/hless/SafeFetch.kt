package com.lagradost.hless

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * True for an address that a provider may legitimately point a stream at.
 * Loopback, unspecified, link-local, private (10/8, 172.16/12, 192.168/16),
 * shared address space (100.64/10), reserved (0/8, 240/4), IPv6 unique-local
 * (fc00::/7) and multicast are not: a provider is third-party code, and the
 * addon must not become a way to make requests into the LAN (5b).
 */
fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false
    val b = address.address
    if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return false
    if (b.size == 4) {
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        if (first == 0) return false
        if (first == 100 && (second and 0xC0) == 0x40) return false
        if (first >= 240) return false
    }
    return true
}

private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/** A host that is an IP literal (OkHttp does not ask [Dns] about those). */
internal fun isIpLiteral(host: String): Boolean = host.contains(':') || IPV4_LITERAL.matches(host)

/** Resolves like the system, but refuses a host if any of its addresses is not public. */
class PublicOnlyDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val allowPrivate: Boolean = false,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (!allowPrivate && addresses.any { !isPublicAddress(it) }) throw UnknownHostException("blocked address")
        return addresses
    }
}

private val BLOCKED_HEADERS = setOf(
    "host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer", "range",
    "keep-alive", "expect",
)
private val HEADER_NAME = Regex("""^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$""")

/**
 * The headers of a link that are safe to replay in a probe: no hop-by-hop or
 * framing headers, no `Range` (set by the probe itself), no `Proxy-*`, and
 * nothing with a control character in it.
 */
fun sanitizeProbeHeaders(headers: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for ((name, value) in headers) {
        val lower = name.lowercase()
        if (!HEADER_NAME.matches(name) || lower in BLOCKED_HEADERS || lower.startsWith("proxy-")) continue
        if (value.length > 4096 || value.any { it != '\t' && (it < ' ' || it > '~') }) continue
        out[name] = value
    }
    return out
}

/** Remembers how far a call got, so a timeout can say where it stood still (DNS, connect, TLS, waiting for headers). */
internal class CallProgress : okhttp3.EventListener() {
    private val startedAt = System.nanoTime()
    private val events = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun at(event: String) {
        events += "$event@${(System.nanoTime() - startedAt) / 1_000_000}"
    }

    override fun callStart(call: okhttp3.Call) = at("call")
    override fun dnsStart(call: okhttp3.Call, domainName: String) = at("dns")
    override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<InetAddress>) = at("dns done")
    override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) = at("connect")
    override fun secureConnectStart(call: okhttp3.Call) = at("tls")
    override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) = at("tls done")
    override fun connectEnd(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) = at("connected")
    override fun requestHeadersEnd(call: okhttp3.Call, request: Request) = at("request sent")
    override fun responseHeadersStart(call: okhttp3.Call) = at("response")

    /** `call@0 dns@1 dns done@5029`: each stage and the millisecond it was reached; now included. */
    fun describe(): String {
        at("now")
        return synchronized(events) { events.joinToString(" ") }
    }
}

class ProbeResponse(
    val status: Int,
    /** Lower-cased header names; the first value of each. */
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** One bounded HTTP request, or null on any failure (network, blocked address, timeout, redirect limit). */
interface ProbeHttp {
    suspend fun fetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        range: String? = null,
    ): ProbeResponse?
}

/**
 * The probe's HTTP client (5b): http and https only, redirects followed by
 * hand (at most 3, every hop checked), connections only to public addresses,
 * short timeouts, a bounded body.
 */
class SafeFetch(private val allowPrivate: Boolean = false) : ProbeHttp {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .dns(PublicOnlyDns(allowPrivate = allowPrivate))
        .build()

    override suspend fun fetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        range: String?,
    ): ProbeResponse? = withContext(Dispatchers.IO) {
        val progress = CallProgress()
        val callClient = client.newBuilder().eventListener(progress).build()
        try {
            var current: HttpUrl = url.toHttpUrlOrNull() ?: return@withContext null
            val sent = sanitizeProbeHeaders(headers)
            repeat(MAX_REDIRECTS + 1) {
                if (current.scheme != "http" && current.scheme != "https") return@withContext null
                if (!allowPrivate && isIpLiteral(current.host) && !isPublicAddress(InetAddress.getByName(current.host))) {
                    return@withContext null
                }
                val builder = Request.Builder().url(current).method(method, null)
                for ((name, value) in sent) builder.header(name, value)
                if (!sent.keys.any { it.equals("User-Agent", ignoreCase = true) }) builder.header("User-Agent", DEFAULT_AGENT)
                if (range != null) builder.header("Range", range)
                callClient.newCall(builder.build()).execute().use { response ->
                    val location = response.header("Location")
                    if (response.code in REDIRECTS && location != null) {
                        current = current.resolve(location) ?: return@withContext null
                        return@use
                    }
                    val body = if (method == "HEAD") ByteArray(0) else response.body?.byteStream()?.readNBytes(maxBytes) ?: ByteArray(0)
                    val seen = LinkedHashMap<String, String>()
                    for (name in response.headers.names()) seen[name.lowercase()] = response.header(name).orEmpty()
                    return@withContext ProbeResponse(response.code, seen, body)
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class name only: the message of a network exception can carry the URL.
            val host = url.toHttpUrlOrNull()?.host ?: "?"
            RateLimitedLog.log("probe-failed:$host", "hless: probe failed host=$host: ${e.javaClass.simpleName}, ${progress.describe()}")
            null
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 3
        const val DEFAULT_AGENT = "Mozilla/5.0"
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
