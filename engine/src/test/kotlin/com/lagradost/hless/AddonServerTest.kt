package com.lagradost.hless

import com.lagradost.macos.models.DetailsDto
import com.lagradost.macos.models.EpisodeDto
import com.lagradost.macos.models.HealthDto
import com.lagradost.macos.models.HomePageDto
import com.lagradost.macos.models.HomePageListDto
import com.lagradost.macos.models.LinksResponseDto
import com.lagradost.macos.models.PluginDto
import com.lagradost.macos.models.ProviderInfoDto
import com.lagradost.macos.models.RepoPluginDto
import com.lagradost.macos.models.RepositoryDto
import com.lagradost.macos.models.SearchResultDto
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

private class FakeClient(
    private val providers: List<ProviderInfoDto> = emptyList(),
    private val homeHandler: (String, Int) -> HomePageDto = { p, _ -> HomePageDto(provider = p, page = 1, lists = emptyList()) },
    private val searchResults: List<SearchResultDto> = emptyList(),
    private val details: DetailsDto? = null,
) : EngineClient {
    val homeCalls = mutableListOf<Pair<String, Int>>()
    val searchCalls = mutableListOf<Pair<String, String>>()

    override suspend fun health(): HealthDto = error("not used")
    override suspend fun awaitReady(timeoutMs: Long) = Unit
    override suspend fun providers(timeoutMs: Long): List<ProviderInfoDto> = providers
    override suspend fun search(providerId: String, query: String, timeoutMs: Long): List<SearchResultDto> {
        searchCalls += providerId to query
        return searchResults
    }
    override suspend fun details(providerId: String, url: String, timeoutMs: Long): DetailsDto =
        details ?: error("no details")
    override suspend fun home(providerId: String, page: Int, timeoutMs: Long): HomePageDto {
        homeCalls += providerId to page
        return homeHandler(providerId, page)
    }
    override suspend fun links(providerId: String, data: String, budgetMs: Long): LinksResponseDto = error("not used")
    override suspend fun repos(timeoutMs: Long): List<RepositoryDto> = emptyList()
    override suspend fun syncRepo(url: String, installAll: Boolean, timeoutMs: Long): RepositoryDto = error("not used")
    override suspend fun consent(url: String, timeoutMs: Long) = Unit
    override suspend fun repoPlugins(url: String, timeoutMs: Long): List<RepoPluginDto> = emptyList()
    override suspend fun install(repoUrl: String, internalName: String, timeoutMs: Long): PluginDto = error("not used")
    override suspend fun plugins(timeoutMs: Long): List<PluginDto> = emptyList()
}

class AddonServerTest {

    @Test
    fun `manifest has the stream catalog and meta resources and CORS headers`() {
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), null) }
            val response = client.get("/manifest.json")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowHeaders])
            val json = Json.parseToJsonElement(response.body<String>())
            assertEquals(
                listOf("stream", "catalog", "meta"),
                json.jsonObject["resources"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("tt", "kitsu:", "hless:"),
                json.jsonObject["idPrefixes"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `manifest lists a catalog per provider with a main page, per covered type`() {
        val engine = FakeClient(providers = listOf(
            ProviderInfoDto(name = "A", providerId = "a", mainUrl = "https://a", language = "en", tvTypes = listOf("Movie"), hasMainPage = true),
            ProviderInfoDto(name = "B", providerId = "b", mainUrl = "https://b", language = "en", tvTypes = listOf("TvSeries"), hasMainPage = true),
            ProviderInfoDto(name = "C", providerId = "c", mainUrl = "https://c", language = "en", tvTypes = listOf("Movie"), hasMainPage = false),
        ))
        testApplication {
            application { addonModule(RecordingService(), engine, null) }
            val json = Json.parseToJsonElement(client.get("/manifest.json").body<String>())
            val catalogs = json.jsonObject["catalogs"]!!.jsonArray.map { it.jsonObject }
            assertEquals(
                listOf(
                    "movie" to "hless-" + HlessId.b64url("a"),
                    "series" to "hless-" + HlessId.b64url("b"),
                ),
                catalogs.map { it["type"]!!.jsonPrimitive.content to it["id"]!!.jsonPrimitive.content },
            )
            val extras = catalogs.single { it["id"]!!.jsonPrimitive.content == "hless-" + HlessId.b64url("a") }
                .get("extra")!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertEquals(listOf("search", "skip"), extras)
        }
    }

    @Test
    fun `catalog route maps the home page to hless metas, filtered by type`() {
        val items = listOf(
            SearchResultDto(name = "A Movie", url = "https://a/m1", provider = "a", type = "Movie"),
            SearchResultDto(name = "A Show", url = "https://a/s1", provider = "a", type = "TvSeries"),
        )
        val engine = FakeClient(
            homeHandler = { p, _ -> HomePageDto(provider = p, page = 1, lists = listOf(HomePageListDto(name = "l", isHorizontal = true, items = items))) },
        )
        val id = "hless-" + HlessId.b64url("a")
        testApplication {
            application { addonModule(RecordingService(), engine, null) }
            val response = client.get("/catalog/movie/$id.json")
            assertEquals(HttpStatusCode.OK, response.status)
            val metas = Json.parseToJsonElement(response.body<String>()).jsonObject["metas"]!!.jsonArray.map { it.jsonObject }
            assertEquals(1, metas.size)
            assertEquals("A Movie", metas[0]["name"]!!.jsonPrimitive.content)
            assertEquals("movie", metas[0]["type"]!!.jsonPrimitive.content)
            assertEquals(HlessId("a", "https://a/m1").encode(), metas[0]["id"]!!.jsonPrimitive.content)
            assertEquals(listOf("a" to 1), engine.homeCalls)
        }
    }

    @Test
    fun `catalog search route calls the engine search`() {
        val engine = FakeClient(searchResults = listOf(
            SearchResultDto(name = "Found", url = "https://a/f", provider = "a", type = "Movie"),
        ))
        val id = "hless-" + HlessId.b64url("a")
        testApplication {
            application { addonModule(RecordingService(), engine, null) }
            val response = client.get("/catalog/movie/$id/search=found.json")
            assertEquals(HttpStatusCode.OK, response.status)
            val metas = Json.parseToJsonElement(response.body<String>()).jsonObject["metas"]!!.jsonArray.map { it.jsonObject }
            assertEquals(1, metas.size)
            assertEquals("Found", metas[0]["name"]!!.jsonPrimitive.content)
            assertEquals(listOf("a" to "found"), engine.searchCalls)
        }
    }

    @Test
    fun `catalog skip route pages the home page`() {
        val engine = FakeClient()
        val id = "hless-" + HlessId.b64url("a")
        testApplication {
            application { addonModule(RecordingService(), engine, null) }
            val response = client.get("/catalog/movie/$id/skip=50.json")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("a" to 2), engine.homeCalls)
        }
    }

    @Test
    fun `meta route maps details to a stremio meta with videos`() {
        val details = DetailsDto(
            provider = "a",
            url = "https://a/m1",
            name = "A Movie",
            type = "Movie",
            posterUrl = "https://a/poster.jpg",
            plot = "plot",
            year = 2020,
            tags = listOf("Action"),
            episodes = listOf(
                EpisodeDto(data = "d1", name = "E1", season = 1, episode = 1),
                EpisodeDto(data = "d2", season = 1, episode = 2),
            ),
        )
        val engine = FakeClient(details = details)
        val id = HlessId("a", "https://a/m1").encode()
        testApplication {
            application { addonModule(RecordingService(), engine, null) }
            val response = client.get("/meta/movie/$id.json")
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.body<String>()).jsonObject
            val meta = json["meta"]!!.jsonObject
            assertEquals("A Movie", meta["name"]!!.jsonPrimitive.content)
            assertEquals("2020", meta["releaseInfo"]!!.jsonPrimitive.content)
            val videos = meta["videos"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, videos.size)
            assertEquals(HlessId("a", "https://a/m1", 1, 1).encode(), videos[0]["id"]!!.jsonPrimitive.content)
            assertEquals("E1", videos[0]["title"]!!.jsonPrimitive.content)
            assertEquals("Episode 2", videos[1]["title"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `stream route decodes the id and calls the service`() {
        val service = RecordingService(
            listOf(StremioStream("CS 1080p", "desc", "https://cdn.example/a.mp4")),
        )
        testApplication {
            application { addonModule(service, FakeClient(), null) }
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
            application { addonModule(RecordingService(), FakeClient(), "s3cret") }
            assertEquals(HttpStatusCode.NotFound, client.get("/manifest.json").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/stream/movie/tt0111161.json").status)
            assertEquals(HttpStatusCode.OK, client.get("/s3cret/manifest.json").status)
            assertEquals(HttpStatusCode.OK, client.get("/s3cret/stream/movie/tt0111161.json").status)
        }
    }

    @Test
    fun `service failure answers an empty stream list`() {
        testApplication {
            application { addonModule(RecordingService(fail = true), FakeClient(), null) }
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
            application { addonModule(RecordingService(), FakeClient(), null) }
            val response = client.options("/stream/movie/tt0111161.json")
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }
}
