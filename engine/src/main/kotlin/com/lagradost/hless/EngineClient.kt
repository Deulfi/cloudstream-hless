package com.lagradost.hless

import com.lagradost.macos.models.DetailsDto
import com.lagradost.macos.models.HealthDto
import com.lagradost.macos.models.HomePageDto
import com.lagradost.macos.models.LinksRequestDto
import com.lagradost.macos.models.LinksResponseDto
import com.lagradost.macos.models.PluginDto
import com.lagradost.macos.models.PluginInstallRequestDto
import com.lagradost.macos.models.ProviderInfoDto
import com.lagradost.macos.models.RepoConsentRequestDto
import com.lagradost.macos.models.RepoPluginDto
import com.lagradost.macos.models.ReposSyncRequestDto
import com.lagradost.macos.models.RepositoryDto
import com.lagradost.macos.models.SearchResultDto
import com.lagradost.macos.models.SolveCallbackRequestDto
import com.lagradost.macos.models.SolvePendingDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A non-2xx engine response. [code] comes from the JSON body when present. */
class EngineException(
    val status: Int,
    val code: String?,
    message: String,
) : Exception(message)

/** Typed client for the engine's loopback HTTP API. */
interface EngineClient {
    suspend fun health(): HealthDto

    suspend fun awaitReady(timeoutMs: Long)

    suspend fun providers(timeoutMs: Long = 30_000): List<ProviderInfoDto>

    suspend fun search(providerId: String, query: String, timeoutMs: Long): List<SearchResultDto>

    suspend fun details(providerId: String, url: String, timeoutMs: Long): DetailsDto

    /** GET /home — one page of the provider's main shelves. */
    suspend fun home(providerId: String, page: Int, timeoutMs: Long = 30_000): HomePageDto

    /** [budgetMs] is clamped to 1000..45000, the engine's valid range. */
    suspend fun links(providerId: String, data: String, budgetMs: Long): LinksResponseDto

    suspend fun repos(timeoutMs: Long = 30_000): List<RepositoryDto>

    suspend fun syncRepo(url: String, installAll: Boolean, timeoutMs: Long = 180_000): RepositoryDto

    suspend fun consent(url: String, timeoutMs: Long = 30_000)

    suspend fun repoPlugins(url: String, timeoutMs: Long = 30_000): List<RepoPluginDto>

    suspend fun install(repoUrl: String, internalName: String, timeoutMs: Long = 120_000): PluginDto

    suspend fun plugins(timeoutMs: Long = 30_000): List<PluginDto>

    /** Long-polls parked Cloudflare challenge tasks (GET /solve/pending). */
    /**
     * POST /extensions/settings `{"autoUpdate": enabled}`. The engine ships with it off, and
     * then a repository re-sync never applies an extension's fix. Default: do nothing.
     */
    suspend fun setAutoUpdate(enabled: Boolean) {}

    suspend fun pendingSolves(waitMs: Long): List<SolvePendingDto>

    /** Hands solved cookies back to the engine (POST /solve-callback). */
    suspend fun completeSolve(taskId: String, targetUrl: String, cookies: String)
}

class OkHttpEngineClient(
    private val baseUrl: String,
    private val token: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) : EngineClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val base: HttpUrl = baseUrl.toHttpUrl()

    override suspend fun health(): HealthDto =
        request("GET", "/health", auth = false, timeoutMs = 5_000) {
            json.decodeFromString<HealthDto>(it)
        }

    override suspend fun awaitReady(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val health = runCatching { health() }.getOrNull()
            if (health != null) {
                val phase = health.extensions.phase
                if (phase == "ready" || phase == "safe_mode") return
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("engine not ready after ${timeoutMs} ms")
            }
            delay(500)
        }
    }

    override suspend fun providers(timeoutMs: Long): List<ProviderInfoDto> =
        request("GET", "/providers", timeoutMs = timeoutMs) {
            json.decodeFromString<List<ProviderInfoDto>>(it)
        }

    override suspend fun search(providerId: String, query: String, timeoutMs: Long): List<SearchResultDto> =
        request(
            "GET",
            "/search",
            query = { it.addQueryParameter("q", query).addQueryParameter("plugins", providerId) },
            timeoutMs = timeoutMs,
        ) {
            json.decodeFromString<List<SearchResultDto>>(it)
        }

    override suspend fun details(providerId: String, url: String, timeoutMs: Long): DetailsDto =
        request(
            "GET",
            "/details",
            query = { it.addQueryParameter("plugin", providerId).addQueryParameter("url", url) },
            timeoutMs = timeoutMs,
        ) {
            json.decodeFromString<DetailsDto>(it)
        }

    override suspend fun home(providerId: String, page: Int, timeoutMs: Long): HomePageDto =
        request(
            "GET",
            "/home",
            query = { it.addQueryParameter("plugin", providerId).addQueryParameter("page", page.toString()) },
            timeoutMs = timeoutMs,
        ) {
            json.decodeFromString<HomePageDto>(it)
        }

    override suspend fun links(providerId: String, data: String, budgetMs: Long): LinksResponseDto {
        val budget = budgetMs.coerceIn(1_000L, 45_000L)
        val body = json.encodeToString(LinksRequestDto(plugin = providerId, data = data, budgetMs = budget))
        return request(
            "POST",
            "/links",
            body = body,
            // The engine may take the full budget before answering with a timeout failure.
            timeoutMs = budget + 5_000,
        ) {
            json.decodeFromString<LinksResponseDto>(it)
        }
    }

    override suspend fun repos(timeoutMs: Long): List<RepositoryDto> =
        request("GET", "/repos", timeoutMs = timeoutMs) {
            json.decodeFromString<List<RepositoryDto>>(it)
        }

    override suspend fun syncRepo(url: String, installAll: Boolean, timeoutMs: Long): RepositoryDto =
        request(
            "POST",
            "/repos/sync",
            body = json.encodeToString(
                ReposSyncRequestDto(repoUrl = url, installAll = installAll),
            ),
            timeoutMs = timeoutMs,
        ) {
            json.decodeFromString<RepositoryDto>(it)
        }

    override suspend fun consent(url: String, timeoutMs: Long) {
        request(
            "POST",
            "/repos/consent",
            body = json.encodeToString(RepoConsentRequestDto(repoUrl = url, allow = true)),
            timeoutMs = timeoutMs,
        ) {
        }
    }

    override suspend fun repoPlugins(url: String, timeoutMs: Long): List<RepoPluginDto> =
        request(
            "GET",
            "/repos/plugins",
            query = { it.addQueryParameter("repoUrl", url) },
            timeoutMs = timeoutMs,
        ) {
            json.decodeFromString<List<RepoPluginDto>>(it)
        }

    override suspend fun install(repoUrl: String, internalName: String, timeoutMs: Long): PluginDto =
        request(
            "POST",
            "/plugins/install",
            body = json.encodeToString(
                PluginInstallRequestDto(repoUrl = repoUrl, internalName = internalName),
            ),
            timeoutMs = timeoutMs,
        ) {
            json.decodeFromString<PluginDto>(it)
        }

    override suspend fun plugins(timeoutMs: Long): List<PluginDto> =
        request("GET", "/plugins", timeoutMs = timeoutMs) {
            json.decodeFromString<List<PluginDto>>(it)
        }

    override suspend fun pendingSolves(waitMs: Long): List<SolvePendingDto> =
        request(
            "GET",
            "/solve/pending",
            query = { it.addQueryParameter("waitMs", waitMs.toString()) },
            // The engine holds the request until waitMs elapses.
            timeoutMs = waitMs + 10_000,
        ) {
            json.decodeFromString<List<SolvePendingDto>>(it)
        }

    override suspend fun setAutoUpdate(enabled: Boolean) {
        request(
            "POST",
            "/extensions/settings",
            body = """{"autoUpdate":$enabled}""",
            timeoutMs = 30_000,
        ) {
        }
    }

    override suspend fun completeSolve(taskId: String, targetUrl: String, cookies: String) {
        request(
            "POST",
            "/solve-callback",
            body = json.encodeToString(
                SolveCallbackRequestDto(taskId = taskId, targetUrl = targetUrl, cookies = cookies),
            ),
            timeoutMs = 10_000,
        ) {
        }
    }

    private suspend fun <T> request(
        method: String,
        path: String,
        query: (HttpUrl.Builder) -> Unit = {},
        body: String? = null,
        timeoutMs: Long,
        auth: Boolean = true,
        decode: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        var attempt = 0
        suspend fun tryOnce(): T {
            val url = base.newBuilder()
                .encodedPath(path)
                .apply { query(this) }
                .build()
            val builder = Request.Builder()
                .url(url)
                .method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
            if (auth) builder.header("Authorization", "Bearer $token")
            val call = httpClient.newCall(builder.build())
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            val response = call.execute()
            response.use {
                val status = it.code
                val text = it.body?.string().orEmpty()
                return when {
                    status in 200..299 -> decode(text)
                    // 429: the engine's rate limiter refused; wait and retry, at most 3 times.
                    status == 429 && attempt < 3 -> {
                        attempt++
                        delay(250)
                        tryOnce()
                    }
                    else -> throw parseError(status, text)
                }
            }
        }
        tryOnce()
    }

    private fun parseError(status: Int, body: String): EngineException {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val obj = parsed?.jsonObject
        val code = obj?.let {
            it.get("failure")?.jsonObject?.get("code")?.jsonPrimitive?.content
                ?: it.get("code")?.jsonPrimitive?.content
        }
        val message = obj?.get("error")?.jsonPrimitive?.content
            ?: "engine returned HTTP $status"
        return EngineException(status, code, message)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()!!
    }
}
