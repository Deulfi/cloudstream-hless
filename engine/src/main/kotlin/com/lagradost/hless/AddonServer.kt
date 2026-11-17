package com.lagradost.hless

import com.lagradost.macos.models.DetailsDto
import com.lagradost.macos.models.ProviderInfoDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
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

/** Resolves streams for one Stremio id; implemented by 2e. */
interface StreamService {
    suspend fun streams(type: String, id: String): List<StremioStream>
}

const val ADDON_VERSION: String = "0.1.0"

/**
 * The Stremio addon: manifest, stream/catalog/meta endpoints, CORS, optional
 * secret prefix. [secret] is exactly as configured; a blank one behaves like
 * no secret. [client] serves the dynamic manifest and the catalog/meta
 * routes (3b).
 */
fun Application.addonModule(service: StreamService, client: EngineClient, secret: String?) {
    install(ContentNegotiation) { json(addonJson) }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "*")
        proceed()
    }
    val manifestCache = ManifestCache(client)
    val prefix = secret?.trim()?.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
    routing {
        // Stremio sends a bare OPTIONS probe before each GET; answer 204 to all.
        options("/{path...}") { call.respond(HttpStatusCode.NoContent) }
        get("/healthz") { call.respondText("ok") }
        val mount: Route.() -> Unit = {
            get("/manifest.json") { call.respond(manifestCache.get()) }
            get("/stream/{type}/{id}.json") { call.streamRoute(service) }
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
        }
        if (prefix.isEmpty()) mount() else route(prefix) { mount() }
    }
}

/**
 * The manifest is built from the engine's providers and cached for 60 s.
 * A failed rebuild keeps the previous manifest.
 */
private class ManifestCache(private val client: EngineClient) {
    @Volatile
    private var at = 0L
    @Volatile
    private var value: JsonObject = manifest(emptyList())

    suspend fun get(): JsonObject {
        if (System.currentTimeMillis() - at < 60_000L) return value
        val providers = try {
            client.providers()
        } catch (e: Exception) {
            null
        }
        if (providers != null) {
            value = manifest(providers)
            at = System.currentTimeMillis()
        }
        return value
    }
}

private suspend fun ApplicationCall.streamRoute(service: StreamService) {
    val type = parameters["type"].orEmpty()
    val id = parameters["id"].orEmpty()
    val streams = if (type == "movie" || type == "series" || type == "anime") {
        try {
            service.streams(type, id)
        } catch (e: Exception) {
            // Never log the exception message: it may carry a stream URL.
            println("hless: stream resolution failed for $type/$id: ${e.javaClass.simpleName}")
            emptyList()
        }
    } else {
        emptyList()
    }
    response.headers.append(
        HttpHeaders.CacheControl,
        if (streams.isEmpty()) "no-store" else "max-age=60",
    )
    respond(StreamsResponse(streams))
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
    respond(meta)
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

private fun manifest(providers: List<ProviderInfoDto>): JsonObject = buildJsonObject {
    put("id", "community.cloudstream.hless")
    put("version", ADDON_VERSION)
    put("name", "CloudStream hless")
    put("description", "Streams resolved by CloudStream extensions on your own server.")
    put("resources", JsonArray(listOf(JsonPrimitive("stream"), JsonPrimitive("catalog"), JsonPrimitive("meta"))))
    put("types", JsonArray(listOf(JsonPrimitive("movie"), JsonPrimitive("series"), JsonPrimitive("anime"))))
    put("idPrefixes", JsonArray(listOf(JsonPrimitive("tt"), JsonPrimitive("kitsu:"), JsonPrimitive("hless:"))))
    put("catalogs", JsonArray(catalogs(providers)))
    put("behaviorHints", buildJsonObject { put("configurable", false) })
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
        addonModule(service, client, Config.secret)
    }.start(wait = true)
}
