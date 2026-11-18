package com.lagradost.hless

import com.lagradost.macos.models.StreamLinkDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** Learns the facts of one link (5b); null means unknown. Never throws except for cancellation. */
interface Prober {
    suspend fun probe(link: StreamLinkDto): LinkFacts?
}

/** A prober that never learns anything; for tests and for switching probing off. */
object NoProbe : Prober {
    override suspend fun probe(link: StreamLinkDto): LinkFacts? = null
}

/**
 * Fetches a link's playlist (HLS) or its headers (direct files) with the
 * link's own request headers and reads the facts out of them.
 *
 * Results are kept in memory only: these URLs are usually signed and
 * short-lived. Facts live 10 minutes, a failed probe 1 minute.
 */
class LinkProbe(
    private val http: ProbeHttp = SafeFetch(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val factsTtlMs: Long = 10 * 60_000L,
    private val missTtlMs: Long = 60_000L,
    private val maxEntries: Int = 500,
    concurrency: Int = 16,
    private val timeoutMs: Long = 3_000L,
) : Prober {
    private class Entry(val facts: LinkFacts?, val at: Long)

    private val semaphore = Semaphore(concurrency)
    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > maxEntries
    }

    override suspend fun probe(link: StreamLinkDto): LinkFacts? {
        if (link.type != "M3U8" && link.type != "VIDEO") return null
        val key = keyOf(link)
        synchronized(cache) { cache[key] }?.let { entry ->
            val ttl = if (entry.facts != null) factsTtlMs else missTtlMs
            if (clock() - entry.at < ttl) return entry.facts
        }
        val facts = try {
            semaphore.withPermit { withTimeoutOrNull(timeoutMs) { read(link) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        synchronized(cache) { cache[key] = Entry(facts, clock()) }
        return facts
    }

    private suspend fun read(link: StreamLinkDto): LinkFacts? {
        if (link.type == "M3U8") {
            val response = http.fetch("GET", link.url, link.headers, PLAYLIST_LIMIT) ?: return null
            if (response.status != 200) return unusable(link, "HTTP ${response.status}")
            return parseHls(String(response.body, Charsets.UTF_8)) ?: unusable(link, "not a playlist")
        }
        val head = http.fetch("HEAD", link.url, link.headers, 0)
        var size = head?.let { parseFileHeaders(it.status, it.headers["content-length"], it.headers["content-range"]) }
        if (size == null) {
            val ranged = http.fetch("GET", link.url, link.headers, 1, range = "bytes=0-0")
            size = ranged?.let { parseFileHeaders(it.status, it.headers["content-length"], it.headers["content-range"]) }
            if (size == null) return unusable(link, "no size (HEAD ${head?.status}, range ${ranged?.status})")
        }
        return LinkFacts(Kind.FILE, sizeBytes = size)
    }

    /** Says once in a while which host gave nothing usable and why; host and status only, never the URL. */
    private fun unusable(link: StreamLinkDto, why: String): LinkFacts? {
        val host = link.url.toHttpUrlOrNull()?.host ?: "?"
        RateLimitedLog.log("probe-unusable:$host", "hless: probe found nothing host=$host: $why")
        return null
    }

    private fun keyOf(link: StreamLinkDto): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(link.type.toByteArray())
        digest.update(0)
        digest.update(link.url.toByteArray())
        for ((name, value) in link.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
            digest.update(0)
            digest.update(name.lowercase().toByteArray())
            digest.update(0)
            digest.update(value.toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PLAYLIST_LIMIT = 512 * 1024
    }
}
