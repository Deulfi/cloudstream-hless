package com.lagradost.hless

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
 * The Stremio addon: manifest, stream endpoint, CORS, optional secret prefix.
 * [secret] is exactly as configured; a blank one behaves like no secret.
 */
fun Application.addonModule(service: StreamService, secret: String?) {
    install(ContentNegotiation) { json(addonJson) }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "*")
        proceed()
    }
    val prefix = secret?.trim()?.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
    routing {
        // Stremio sends a bare OPTIONS probe before each GET; answer 204 to all.
        options("/{path...}") { call.respond(HttpStatusCode.NoContent) }
        get("/healthz") { call.respondText("ok") }
        val mount: Route.() -> Unit = {
            get("/manifest.json") { call.respond(manifest()) }
            get("/stream/{type}/{id}.json") { call.streamRoute(service) }
        }
        if (prefix.isEmpty()) mount() else route(prefix) { mount() }
    }
}

private suspend fun ApplicationCall.streamRoute(service: StreamService) {
    val type = parameters["type"].orEmpty()
    val id = parameters["id"].orEmpty()
    val streams = if (type == "movie" || type == "series") {
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

private fun manifest(): JsonObject = buildJsonObject {
    put("id", "community.cloudstream.hless")
    put("version", ADDON_VERSION)
    put("name", "CloudStream hless")
    put("description", "Streams resolved by CloudStream extensions on your own server.")
    put("resources", JsonArray(listOf(JsonPrimitive("stream"))))
    put("types", JsonArray(listOf(JsonPrimitive("movie"), JsonPrimitive("series"))))
    put("idPrefixes", JsonArray(listOf(JsonPrimitive("tt"))))
    put("catalogs", JsonArray(emptyList()))
    put("behaviorHints", buildJsonObject { put("configurable", false) })
}

private val addonJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

/** Starts the addon server on [Config.port] (all interfaces) and blocks. */
fun startAddonServer(service: StreamService) {
    embeddedServer(Netty, host = "0.0.0.0", port = Config.port) {
        addonModule(service, Config.secret)
    }.start(wait = true)
}
