package com.lagradost.hless

import com.lagradost.macos.models.DetailsDto
import com.lagradost.macos.models.ProviderInfoDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What one stream request produced; [complete] is false when slow providers were not waited for (5c). */
class StreamResult(
    val streams: List<StremioStream>,
    val complete: Boolean,
    /** Providers worth waiting for that are still working. */
    val loading: Int = 0,
    /** Links left out by the person's resolution filter. */
    val hiddenQuality: Int = 0,
    /** Links left out by the person's hoster filter. */
    val hiddenHoster: Int = 0,
)

/** Resolves streams for one Stremio id with one request's settings; implemented by 2e. */
interface StreamService {
    suspend fun streams(type: String, id: String, settings: Settings): StreamResult
}

/** Server facts the configuration page needs; never a secret. */
class ServerInfo(val mediaflow: Boolean = false, val publicUrl: String? = null)

const val ADDON_VERSION: String = "0.3.1"

/**
 * The Stremio addon: manifest, stream/catalog/meta endpoints, the
 * configuration page (6a), CORS, optional secret prefix. [secret] is exactly
 * as configured; a blank one behaves like no secret. [client] serves the
 * dynamic manifest and the catalog/meta routes (3b). [defaults] are the
 * server's settings; a `c~<prefs>` path segment lays a person's preferences
 * over them for that request.
 */
fun Application.addonModule(
    service: StreamService,
    client: EngineClient,
    secret: String?,
    defaults: Settings = Settings(),
    server: ServerInfo = ServerInfo(),
    healthy: suspend () -> Boolean = { true },
    stats: ProviderStats = ProviderStats(),
) {
    install(ContentNegotiation) { json(addonJson) }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "*")
        proceed()
    }
    val providers = ProviderCache(client)
    val prefix = secret?.trim()?.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
    routing {
        // Stremio sends a bare OPTIONS probe before each GET; answer 204 to all.
        options("/{path...}") { call.respond(HttpStatusCode.NoContent) }
        // 200 only while the engine behind the addon answers, so a supervisor can restart a dead one.
        // The bare secret address (no trailing slash) opens the configuration page too.
        if (prefix.isNotEmpty()) get(prefix) { call.respondRedirect("$prefix/configure") }
        // The icon is public and outside the secret prefix: Stremio asks for it without the prefix.
        get("/logo.png") {
            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(Logo.png, ContentType.Image.PNG)
        }
        get("/healthz") {
            if (healthy()) call.respondText("ok") else call.respondText("engine not ready", status = HttpStatusCode.ServiceUnavailable)
        }
        val mount: Route.() -> Unit = {
            // Opening the addon's address without a path shows the configuration page.
            get("/") {
                call.respondRedirect("$prefix${call.cfgSegment()}/configure")
            }
            get("/manifest.json") {
                val settings = call.settings(defaults)
                call.respond(manifest(providers.get().filter { askProvider(it.name, settings.rank) }, call.origin(server.publicUrl) + "/logo.png"))
            }
            get("/stream/{type}/{id}.json") {
                val configureUrl = call.origin(server.publicUrl) + prefix + call.cfgSegment() + "/configure"
                call.streamRoute(service, call.settings(defaults), configureUrl)
            }
            get("/catalog/{type}/{id}.json") {
                call.catalogRoute(client, type = call.parameters["type"], id = call.parameters["id"])
            }
            get("/catalog/{type}/{id}/search={q}.json") {
                call.catalogRoute(client, type = call.parameters["type"], id = call.parameters["id"], query = call.parameters["q"])
            }
            get("/catalog/{type}/{id}/skip={n}.json") {
                call.catalogRoute(client, type = call.parameters["type"], id = call.parameters["id"], skip = call.parameters["n"]?.toIntOrNull())
            }
            get("/meta/{type}/{id}.json") {
                call.metaRoute(client, type = call.parameters["type"], id = call.parameters["id"])
            }
            get("/configure") { call.configureRoute() }
            get("/api/providers") {
                // Names only: no provider ids, no URLs. Providers the server forbids are not offered.
                val list = providers.get().filter { askProvider(it.name, defaults.rank) }
                call.response.headers.append(HttpHeaders.CacheControl, "max-age=60")
                val array = JsonArray(list.map { provider ->
                    buildJsonObject {
                        put("name", provider.name)
                        put("language", provider.language)
                        put("types", JsonArray(provider.tvTypes.map { JsonPrimitive(it) }))
                        val counts = stats.counts(provider.name)
                        put("hits", counts.hits)
                        put("tries", counts.tries)
                        put("usefulness", stats.classify(provider.name).name.lowercase())
                    }
                })
                call.respondText(array.toString(), ContentType.Application.Json)
            }
            get("/api/hosters") {
                // Hoster names and how often they were seen; nothing else.
                call.response.headers.append(HttpHeaders.CacheControl, "max-age=60")
                val array = JsonArray(stats.hosters().map { (name, seen) ->
                    buildJsonObject {
                        put("name", name)
                        put("seen", seen)
                    }
                })
                call.respondText(array.toString(), ContentType.Application.Json)
            }
            get("/api/server") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respond(serverJson(defaults, server))
            }
        }
        val mountAll: Route.() -> Unit = {
            mount()
            route("/c~{cfg}") { mount() }
        }
        if (prefix.isEmpty()) mountAll() else route(prefix) { mountAll() }
    }
}

private fun ApplicationCall.settings(defaults: Settings): Settings = decodePrefs(parameters["cfg"]).resolve(defaults)

/** `/c~<prefs>` when the request came in under a preferences segment, else empty. */
private fun ApplicationCall.cfgSegment(): String = parameters["cfg"]?.let { "/c~$it" }.orEmpty()

private val SAFE_HOST = Regex("""^[A-Za-z0-9.\-:\[\]]{1,255}$""")

/** The scheme and host this request was addressed to (behind a reverse proxy: as the proxy forwarded it), or the configured public URL. */
private fun ApplicationCall.origin(publicUrl: String?): String {
    if (publicUrl != null) return publicUrl.trimEnd('/')
    val proto = request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim()?.takeIf { it == "http" || it == "https" }
        ?: request.local.scheme
    val host = (request.headers["X-Forwarded-Host"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.headers[HttpHeaders.Host])?.takeIf { SAFE_HOST.matches(it) } ?: "localhost"
    return "$proto://$host"
}

/** The providers, cached for 60 s. A failed refresh keeps the previous list. */
private class ProviderCache(private val client: EngineClient) {
    @Volatile private var at = 0L
    @Volatile private var value: List<ProviderInfoDto> = emptyList()

    suspend fun get(): List<ProviderInfoDto> {
        if (System.currentTimeMillis() - at < 60_000L) return value
        val fresh = try {
            client.providers()
        } catch (e: Exception) {
            null
        }
        if (fresh != null) {
            value = fresh
            at = System.currentTimeMillis()
        }
        return value
    }
}

private suspend fun ApplicationCall.streamRoute(service: StreamService, settings: Settings, configureUrl: String) {
    val type = parameters["type"].orEmpty()
    val id = parameters["id"].orEmpty()
    val result = if (type == "movie" || type == "series" || type == "anime") {
        try {
            service.streams(type, id, settings)
        } catch (e: Exception) {
            // Never log the exception message: it may carry a stream URL.
            println("hless: stream resolution failed for $type/$id: ${e.javaClass.simpleName}")
            StreamResult(emptyList(), false)
        }
    } else {
        StreamResult(emptyList(), true)
    }
    // A partial or empty answer must not be cached: the next request may get more.
    response.headers.append(
        HttpHeaders.CacheControl,
        if (result.streams.isNotEmpty() && result.complete) "max-age=60" else "no-store",
    )
    val note = if (settings.hint) hintRow(settings.display.label, result, configureUrl) else null
    respond(StreamsResponse(if (note != null) result.streams + note else result.streams))
}

private fun sources(n: Int) = if (n == 1) "1 source" else "$n sources"

/** The row at the end of the list that says why it may be incomplete; null when there is nothing to say. */
internal fun hintRow(label: String, result: StreamResult, configureUrl: String): StremioStream? {
    val lines = ArrayList<String>()
    if (result.loading > 0) {
        lines += "\u23F3 ${sources(result.loading)} still loading"
        lines += "Reopen this title in a few seconds"
    }
    if (result.hiddenQuality > 0) lines += "\uD83D\uDD0E ${result.hiddenQuality} hidden by your resolution filter"
    if (result.hiddenHoster > 0) lines += "\uD83D\uDD0E ${result.hiddenHoster} hidden by your hoster filter"
    if (lines.isEmpty()) return null
    val tag = if (result.loading > 0) "\u23F3 loading" else "\uD83D\uDD0E hidden"
    return StremioStream(name = "$label\n$tag", description = lines.joinToString("\n"), externalUrl = configureUrl)
}

private val configureHtml: ByteArray? by lazy {
    Settings::class.java.getResourceAsStream("/hless/configure.html")?.use { it.readBytes() }
}

/** The configuration page (6a): one static file; it never talks to anything but this server. */
private suspend fun ApplicationCall.configureRoute() {
    val html = configureHtml ?: return respond(HttpStatusCode.NotFound)
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:",
    )
    respondBytes(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
}

/** What the page needs to start: whether MediaFlow exists, the public URL, and the defaults. No secrets. */
private fun serverJson(defaults: Settings, server: ServerInfo): JsonObject = buildJsonObject {
    put("mediaflow", server.mediaflow)
    put("publicUrl", server.publicUrl)
    put("denied", JsonArray(defaults.rank.providersDeny.map { JsonPrimitive(it) }))
    put("defaults", buildJsonObject {
        put("langs", JsonArray(defaults.rank.langs.map { JsonPrimitive(it) }))
        put("minH", defaults.rank.minQuality)
        put("maxH", defaults.rank.maxQuality)
        put("first", JsonArray(defaults.rank.providersFirst.map { JsonPrimitive(it) }))
        put("sort", defaults.rank.sort.name.lowercase())
        put("perProv", defaults.rank.maxPerProvider)
        put("total", defaults.rank.maxStreams)
        put("variants", defaults.variants.name.lowercase())
        put("flags", defaults.display.flags.name.lowercase())
        put("icons", defaults.display.icons)
        put("proxy", defaults.proxy)
        put("smart", defaults.rank.smart)
        put("hint", defaults.hint)
        put("order", JsonArray(defaults.rank.order.map { JsonPrimitive(it) }))
        put("hostOff", JsonArray(defaults.rank.hostersOff.map { JsonPrimitive(it) }))
    })
}

/**
 * Catalog (3b): one home page (or the search results) mapped to hless meta
 * items. [skip] picks the home page: 0 -> page 1, 50 -> page 2, and so on.
 */
private suspend fun ApplicationCall.catalogRoute(
    client: EngineClient,
    type: String?,
    id: String?,
    query: String? = null,
    skip: Int? = null,
) {
    if (type != "movie" && type != "series") return respond(HttpStatusCode.BadRequest)
    val providerId = HlessId.b64urlDecode(id?.removePrefix("hless-").orEmpty())
        ?: return respond(HttpStatusCode.BadRequest)
    val items: List<JsonObject> = try {
        val results = if (query != null) {
            client.search(providerId, query, timeoutMs = 30_000)
        } else {
            val page = (skip?.coerceAtLeast(0) ?: 0) / 50 + 1
            client.home(providerId, page, timeoutMs = 30_000).lists.flatMap { it.items }
        }
        results
            .filter { it.type in wantedTypes(type) }
            .map {
                buildJsonObject {
                    put("id", HlessId(providerId, it.url).encode())
                    put("type", type)
                    put("name", it.name)
                    it.posterUrl?.let { put("poster", it) }
                }
            }
    } catch (e: Exception) {
        println("hless: catalog failed for $type/$id: ${e.javaClass.simpleName}")
        return respond(HttpStatusCode.BadGateway)
    }
    response.headers.append(HttpHeaders.CacheControl, "max-age=60")
    respond(buildJsonObject { put("metas", JsonArray(items)) })
}

/** Meta (3b): the engine details of one hless id, mapped to a Stremio meta. */
private suspend fun ApplicationCall.metaRoute(client: EngineClient, type: String?, id: String?) {
    val hless = HlessId.decode(id.orEmpty())
        ?: return respond(HttpStatusCode.BadRequest)
    val meta: JsonObject = try {
        val details = client.details(hless.providerId, hless.url, timeoutMs = 30_000)
        buildJsonObject {
            put("id", id.orEmpty())
            put("type", type.orEmpty().ifEmpty { details.type })
            put("name", details.name)
            details.posterUrl?.let { put("poster", it) }
            details.backgroundPosterUrl?.let { put("background", it) }
            details.plot?.let { put("description", it) }
            details.year?.let { put("releaseInfo", it.toString()) }
            put("genres", JsonArray(details.tags.map { JsonPrimitive(it) }))
            put("videos", JsonArray(videos(hless, details)))
        }
    } catch (e: Exception) {
        println("hless: meta failed for $type/$id: ${e.javaClass.simpleName}")
        return respond(HttpStatusCode.BadGateway)
    }
    response.headers.append(HttpHeaders.CacheControl, "max-age=300")
    respond(buildJsonObject { put("meta", meta) })
}

private fun videos(hless: HlessId, details: DetailsDto): List<JsonObject> =
    details.episodes
        .filter { it.episode != null }
        .map { ep ->
            buildJsonObject {
                put("id", HlessId(hless.providerId, hless.url, ep.season ?: 1, ep.episode).encode())
                put("title", ep.name ?: "Episode ${ep.episode}")
                put("season", ep.season ?: 1)
                put("episode", ep.episode)
                ep.description?.let { put("overview", it) }
                ep.posterUrl?.let { put("thumbnail", it) }
            }
        }

private fun manifest(providers: List<ProviderInfoDto>, logo: String): JsonObject = buildJsonObject {
    put("id", "community.cloudstream.hless")
    put("version", ADDON_VERSION)
    put("name", "Hless")
    put("logo", logo)
    put("description", "Streams resolved by CloudStream extensions on your own server.")
    put("resources", JsonArray(listOf(JsonPrimitive("stream"), JsonPrimitive("catalog"), JsonPrimitive("meta"))))
    put("types", JsonArray(listOf(JsonPrimitive("movie"), JsonPrimitive("series"), JsonPrimitive("anime"))))
    put("idPrefixes", JsonArray(listOf(JsonPrimitive("tt"), JsonPrimitive("kitsu:"), JsonPrimitive("hless:"))))
    put("catalogs", JsonArray(catalogs(providers)))
    put("behaviorHints", buildJsonObject {
        put("configurable", true)
        put("configurationRequired", false)
    })
}

/** One catalog per enabled provider with a main page, per covered type. */
private fun catalogs(providers: List<ProviderInfoDto>): List<JsonObject> =
    providers
        .filter { it.hasMainPage }
        .flatMap { provider ->
            listOf("movie", "series").mapNotNull { type ->
                if (provider.tvTypes.none { it in wantedTypes(type) }) null
                else
                    buildJsonObject {
                        put("type", type)
                        put("id", "hless-" + HlessId.b64url(provider.providerId))
                        put("name", provider.name)
                        put("extra", JsonArray(listOf(
                            buildJsonObject { put("name", "search") },
                            buildJsonObject { put("name", "skip") },
                        )))
                    }
            }
        }

private val addonJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

/** Starts the addon server on [Config.port] (all interfaces) and blocks. */
fun startAddonServer(service: StreamService, client: EngineClient) {
    embeddedServer(Netty, host = "0.0.0.0", port = Config.port) {
        addonModule(
            service, client, Config.secret,
            defaults = Settings.fromConfig(),
            server = ServerInfo(mediaflow = MediaFlow.configured(), publicUrl = Config.publicUrl),
            healthy = {
                runCatching { client.health().extensions.phase in setOf("ready", "safe_mode") }.getOrDefault(false)
            },
        )
    }.start(wait = true)
}
