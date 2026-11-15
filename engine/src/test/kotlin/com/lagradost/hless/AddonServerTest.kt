package com.lagradost.hless

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingService(
    private val result: List<StremioStream> = emptyList(),
    private val fail: Boolean = false,
) : StreamService {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun streams(type: String, id: String): List<StremioStream> {
        calls += type to id
        if (fail) throw IllegalStateException("boom")
        return result
    }
}

class AddonServerTest {

    @Test
    fun `manifest has the stream resource and CORS headers`() {
        testApplication {
            application { addonModule(RecordingService(), null) }
            val response = client.get("/manifest.json")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowHeaders])
            val json = Json.parseToJsonElement(response.body<String>())
            assertEquals(listOf("stream"), json.jsonObject["resources"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test
    fun `stream route decodes the id and calls the service`() {
        val service = RecordingService(
            listOf(StremioStream("CS 1080p", "desc", "https://cdn.example/a.mp4")),
        )
        testApplication {
            application { addonModule(service, null) }
            val response = client.get("/stream/series/tt0944947%3A1%3A2.json")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("series" to "tt0944947:1:2"), service.calls)
            assertEquals("max-age=60", response.headers[HttpHeaders.CacheControl])
            val json = Json.parseToJsonElement(response.body<String>())
            assertEquals(1, json.jsonObject["streams"]!!.jsonArray.size)
        }
    }

    @Test
    fun `secret mounts the routes under the prefix`() {
        testApplication {
            application { addonModule(RecordingService(), "s3cret") }
            assertEquals(HttpStatusCode.NotFound, client.get("/manifest.json").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/stream/movie/tt0111161.json").status)
            assertEquals(HttpStatusCode.OK, client.get("/s3cret/manifest.json").status)
            assertEquals(HttpStatusCode.OK, client.get("/s3cret/stream/movie/tt0111161.json").status)
        }
    }

    @Test
    fun `service failure answers an empty stream list`() {
        testApplication {
            application { addonModule(RecordingService(fail = true), null) }
            val response = client.get("/stream/movie/tt0111161.json")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val json = Json.parseToJsonElement(response.body<String>())
            assertEquals(0, json.jsonObject["streams"]!!.jsonArray.size)
        }
    }

    @Test
    fun `options probe gets 204 with CORS headers`() {
        testApplication {
            application { addonModule(RecordingService(), null) }
            val response = client.options("/stream/movie/tt0111161.json")
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }
}
