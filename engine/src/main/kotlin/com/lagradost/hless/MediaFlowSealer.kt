package com.lagradost.hless

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Turns the server's plain MediaFlow URLs (`…/proxy/stream?d=…&api_password=…&h_…`)
 * into MediaFlow's encrypted, expiring ones (`POST /generate_url`), so the
 * password never reaches a player. Only for the server's own MediaFlow: an
 * address a person put in their link is never contacted.
 *
 * When sealing fails, a shared server ([shared]) hands the link over without the
 * proxy (the password must not leak to strangers); a private one keeps the plain URL.
 */
class MediaFlowSealer(
    private val base: String,
    private val password: String,
    private val shared: Boolean,
    private val post: suspend (url: String, json: String) -> String? = ::httpPost,
    private val clock: () -> Long = System::currentTimeMillis,
    private val expirationSec: Long = 12 * 3600L,
    private val maxEntries: Int = 2_000,
    private val budgetMs: Long = 3_000L,
) {
    private class Entry(val url: String, val at: Long)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > maxEntries
    }

    /** Every stream that goes through this MediaFlow, sealed; the others as they are. A stream that cannot play without the proxy may be left out. */
    suspend fun sealAll(streams: List<StremioStream>): List<StremioStream> = coroutineScope {
        streams.map { stream ->
            async<StremioStream?> {
                val url = stream.url
                if (url == null || !isOurs(url)) return@async stream
                val sealed = withTimeoutOrNull(budgetMs) { seal(url) }
                when {
                    sealed != null -> stream.copy(url = sealed)
                    shared -> unproxied(stream, url)
                    else -> stream
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun isOurs(url: String): Boolean = url.startsWith("$base/proxy/") && url.contains("api_password=")

    /** The sealed URL for one plain MediaFlow URL, or null when MediaFlow did not make one. */
    internal suspend fun seal(plain: String): String? {
        val now = clock()
        synchronized(cache) { cache[plain] }?.let { if (now - it.at < expirationSec * 1000 / 2) return it.url }
        val parsed = plain.toHttpUrlOrNull() ?: return null
        val destination = parsed.queryParameter("d") ?: return null
        val endpoint = plain.substring(base.length).substringBefore('?')
        val body = buildJsonObject {
            put("mediaflow_proxy_url", base)
            put("endpoint", endpoint)
            put("destination_url", destination)
            put("expiration", expirationSec)
            put("api_password", password)
            put("request_headers", buildJsonObject {
                for (name in parsed.queryParameterNames) {
                    if (name.startsWith("h_")) put(name.removePrefix("h_"), parsed.queryParameter(name).orEmpty())
                }
            })
            put("query_params", buildJsonObject {
                for (name in parsed.queryParameterNames) {
                    if (name != "d" && name != "api_password" && !name.startsWith("h_")) put(name, parsed.queryParameter(name).orEmpty())
                }
            })
        }
        val answer = try {
            post("$base/generate_url", body.toString())
        } catch (e: Exception) {
            null
        } ?: return null
        val url = runCatching { (Json.parseToJsonElement(answer) as JsonObject)["url"]?.jsonPrimitive?.content }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.takeIf { !it.contains("api_password=") }
            ?: run {
                RateLimitedLog.log("mediaflow-seal", "hless: MediaFlow did not make an encrypted URL (is /generate_url there?)", 600_000L)
                return null
            }
        synchronized(cache) { cache[plain] = Entry(url, now) }
        return url
    }

    /** The stream without the proxy: the player fetches the link itself, with its headers. */
    private fun unproxied(stream: StremioStream, plain: String): StremioStream? {
        val parsed = plain.toHttpUrlOrNull() ?: return stream
        val destination = parsed.queryParameter("d") ?: return stream
        // DRM needs the proxy; without it the stream cannot play.
        if (parsed.queryParameter("key") != null) return null
        val headers = parsed.queryParameterNames.filter { it.startsWith("h_") }
            .associate { it.removePrefix("h_") to parsed.queryParameter(it).orEmpty() }
        val hints = stream.behaviorHints ?: StreamHints()
        return stream.copy(
            url = destination,
            behaviorHints = if (headers.isEmpty() && destination.startsWith("https://")) hints
            else hints.copy(notWebReady = true, proxyHeaders = ProxyHeaders(headers)),
        )
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
        private val JSON = "application/json".toMediaType()

        private suspend fun httpPost(url: String, json: String): String? = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).post(json.toRequestBody(JSON)).build()).execute().use { response ->
                if (response.code == 200) response.body?.string() else null
            }
        }

        /** The sealer for the server's MediaFlow, or null when there is none or sealing is off. */
        fun fromConfig(): MediaFlowSealer? {
            if (!Config.mediaflowEncrypt) return null
            val base = Config.mediaflowUrl ?: return null
            val password = Config.mediaflowPassword ?: return null
            return MediaFlowSealer(base, password, Config.shared)
        }
    }
}
