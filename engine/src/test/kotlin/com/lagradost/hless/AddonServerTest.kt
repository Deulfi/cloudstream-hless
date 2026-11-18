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
import com.lagradost.macos.models.SolvePendingDto
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
    private val complete: Boolean = true,
    private val loading: Int = 0,
    private val hiddenQuality: Int = 0,
    private val hiddenHoster: Int = 0,
) : StreamService {
    val calls = mutableListOf<Pair<String, String>>()
    var lastSettings: Settings? = null

    override suspend fun streams(type: String, id: String, settings: Settings): StreamResult {
        calls += type to id
        lastSettings = settings
        if (fail) throw IllegalStateException("boom")
        return StreamResult(result, complete, loading, hiddenQuality, hiddenHoster)
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

    override suspend fun pendingSolves(waitMs: Long): List<SolvePendingDto> = emptyList()

    override suspend fun completeSolve(taskId: String, targetUrl: String, cookies: String) {}
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

    @Test
    fun `an id this server did not sign is refused before any extension sees its url`() {
        val engine = FakeClient(details = DetailsDto(provider = "a", url = "x", name = "n", type = "Movie"))
        val real = HlessId("a", "https://a/m1").encode()
        val forged = "hless:" + HlessId.b64url("a") + ":" + HlessId.b64url("http://10.0.0.1/admin") + ":" + real.split(":").last()
        val unsigned = "hless:" + HlessId.b64url("a") + ":" + HlessId.b64url("http://10.0.0.1/admin")
        testApplication {
            application { addonModule(RecordingService(), engine, null) }
            assertEquals(HttpStatusCode.OK, client.get("/meta/movie/$real.json").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/meta/movie/$forged.json").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/meta/movie/$unsigned.json").status)
        }
    }

    @Test
    fun `healthz says no while the engine is down`() {
        var engineUp = true
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), null, healthy = { engineUp }) }
            assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
            engineUp = false
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/healthz").status)
        }
    }

    @Test
    fun `a partial answer is not cached`() {
        val service = RecordingService(listOf(StremioStream("n", "d", "https://cdn.example/a.mp4")), complete = false)
        testApplication {
            application { addonModule(service, FakeClient(), null) }
            val response = client.get("/stream/movie/tt0111161.json")
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(1, Json.parseToJsonElement(response.body<String>()).jsonObject["streams"]!!.jsonArray.size)
        }
    }

    @Test
    fun `the manifest says the addon is configurable`() {
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), null) }
            val json = Json.parseToJsonElement(client.get("/manifest.json").body<String>()).jsonObject
            val hints = json["behaviorHints"]!!.jsonObject
            assertEquals("true", hints["configurable"]!!.jsonPrimitive.content)
            assertEquals("false", hints["configurationRequired"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a c~ segment lays preferences over the server defaults for that request`() {
        val service = RecordingService()
        val segment = encodePrefs(Prefs(minH = 720, sort = "size", off = listOf("Bad")))
        testApplication {
            application {
                addonModule(service, FakeClient(), null, defaults = Settings(rank = RankSettings(providersDeny = listOf("Server"))))
            }
            assertEquals(HttpStatusCode.OK, client.get("/c~$segment/stream/movie/tt0111161.json").status)
            val rank = service.lastSettings!!.rank
            assertEquals(720, rank.minQuality)
            assertEquals(SortMode.SIZE, rank.sort)
            assertEquals(listOf("Server", "Bad"), rank.providersDeny)
            // Without the segment the defaults apply.
            client.get("/stream/movie/tt0111161.json")
            assertEquals(0, service.lastSettings!!.rank.minQuality)
        }
    }

    @Test
    fun `a broken c~ segment falls back to the defaults instead of failing`() {
        val service = RecordingService()
        testApplication {
            application { addonModule(service, FakeClient(), null) }
            assertEquals(HttpStatusCode.OK, client.get("/c~!!!notbase64/stream/movie/tt0111161.json").status)
            assertEquals(Settings(), service.lastSettings)
        }
    }

    @Test
    fun `providers a person switched off lose their catalogs in that manifest`() {
        val engine = FakeClient(providers = listOf(
            ProviderInfoDto(name = "A", providerId = "a", mainUrl = "https://a", language = "en", tvTypes = listOf("Movie"), hasMainPage = true),
            ProviderInfoDto(name = "B", providerId = "b", mainUrl = "https://b", language = "en", tvTypes = listOf("Movie"), hasMainPage = true),
        ))
        val segment = encodePrefs(Prefs(off = listOf("B")))
        testApplication {
            application { addonModule(RecordingService(), engine, null) }
            val json = Json.parseToJsonElement(client.get("/c~$segment/manifest.json").body<String>()).jsonObject
            assertEquals(
                listOf("A"),
                json["catalogs"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `the configuration page is served as one self-contained document`() {
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), null) }
            val response = client.get("/configure")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(true, response.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
            assertEquals(true, response.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val html = response.body<String>()
            assertEquals(true, html.contains("Hless configuration"))
            // It talks to nothing but this server.
            assertEquals(false, html.contains("http://"))
            assertEquals(false, html.contains("https://"))
            assertEquals(HttpStatusCode.OK, client.get("/c~abc/configure").status)
        }
    }

    @Test
    fun `the providers api lists names only`() {
        val engine = FakeClient(providers = listOf(
            ProviderInfoDto(name = "A", providerId = "secret-id", mainUrl = "https://private.example", language = "de", tvTypes = listOf("Movie")),
            ProviderInfoDto(name = "Hidden", providerId = "h", mainUrl = "https://h", language = "en", tvTypes = listOf("Movie")),
        ))
        testApplication {
            application {
                addonModule(RecordingService(), engine, null, defaults = Settings(rank = RankSettings(providersDeny = listOf("Hidden"))))
            }
            val body = client.get("/api/providers").body<String>()
            val list = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
            assertEquals(listOf("A"), list.map { it["name"]!!.jsonPrimitive.content })
            assertEquals(setOf("name", "language", "types", "hits", "tries", "usefulness"), list.single().keys)
            assertEquals(false, body.contains("secret-id"))
            assertEquals(false, body.contains("private.example"))
        }
    }

    @Test
    fun `the server api shows the defaults and nothing else`() {
        testApplication {
            application {
                addonModule(
                    RecordingService(), FakeClient(), null,
                    defaults = Settings(rank = RankSettings(langs = listOf("de", "en"), minQuality = 720)),
                    server = ServerInfo(mediaflow = true, publicUrl = "https://hless.example"),
                )
            }
            val json = Json.parseToJsonElement(client.get("/api/server").body<String>()).jsonObject
            assertEquals(setOf("mediaflow", "publicUrl", "denied", "defaults"), json.keys)
            assertEquals("true", json["mediaflow"]!!.jsonPrimitive.content)
            assertEquals("https://hless.example", json["publicUrl"]!!.jsonPrimitive.content)
            val defaults = json["defaults"]!!.jsonObject
            assertEquals(
                setOf("langs", "minH", "maxH", "first", "sort", "perProv", "total", "variants", "flags", "icons", "proxy", "smart", "hint", "order", "hostOff"),
                defaults.keys,
            )
            assertEquals("720", defaults["minH"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `the new routes honour the secret prefix too`() {
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), "s3cret") }
            for (path in listOf("/configure", "/api/server", "/api/providers", "/api/hosters", "/c~abc/configure", "/c~abc/manifest.json")) {
                assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
                assertEquals(HttpStatusCode.OK, client.get("/s3cret$path").status, "/s3cret$path")
            }
        }
    }

    @Test
    fun `the address without a path opens the configuration page, keeping prefix and preferences`() {
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), null) }
            val plain = createClient { followRedirects = false }
            val root = plain.get("/")
            assertEquals(HttpStatusCode.Found, root.status)
            assertEquals("/configure", root.headers[HttpHeaders.Location])
            assertEquals("/c~abc/configure", plain.get("/c~abc/").headers[HttpHeaders.Location])
        }
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), "s3cret") }
            val plain = createClient { followRedirects = false }
            assertEquals("/s3cret/configure", plain.get("/s3cret").headers[HttpHeaders.Location])
            assertEquals("/s3cret/configure", plain.get("/s3cret/").headers[HttpHeaders.Location])
            assertEquals(HttpStatusCode.NotFound, plain.get("/").status)
        }
    }

    @Test
    fun `the icon is a public png, also when a secret is set`() {
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), "s3cret") }
            val response = client.get("/logo.png")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("image/png", response.headers[HttpHeaders.ContentType])
            assertEquals(true, response.headers[HttpHeaders.CacheControl]!!.contains("max-age"))
            val bytes = response.body<ByteArray>()
            assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), bytes.take(4).map { it.toInt() and 0xFF })
            assertEquals(HttpStatusCode.NotFound, client.get("/s3cret/logo.png").status)
        }
    }

    @Test
    fun `the manifest is called Hless and points at the icon, as the proxy forwarded the request`() {
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), null) }
            fun manifest(block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}) = kotlinx.coroutines.runBlocking {
                Json.parseToJsonElement(client.get("/manifest.json", block).body<String>()).jsonObject
            }
            val plain = manifest()
            assertEquals("Hless", plain["name"]!!.jsonPrimitive.content)
            assertEquals(true, plain["logo"]!!.jsonPrimitive.content.endsWith("/logo.png"))
            val proxied = manifest {
                headers.append("X-Forwarded-Proto", "https")
                headers.append("X-Forwarded-Host", "hless.example.org")
            }
            assertEquals("https://hless.example.org/logo.png", proxied["logo"]!!.jsonPrimitive.content)
            val hostile = manifest { headers.append("X-Forwarded-Host", "evil.example/\"><script>") }
            assertEquals(true, hostile["logo"]!!.jsonPrimitive.content.startsWith("http://localhost") || hostile["logo"]!!.jsonPrimitive.content.contains("localhost"))
        }
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), null, server = ServerInfo(publicUrl = "https://p.example/")) }
            val json = Json.parseToJsonElement(client.get("/manifest.json").body<String>()).jsonObject
            assertEquals("https://p.example/logo.png", json["logo"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a note row says what is still loading and what a filter hid, and links to the configuration`() {
        val streams = listOf(StremioStream("n", "d", "https://cdn.example/a.mp4"))
        testApplication {
            application {
                addonModule(RecordingService(streams, complete = false, loading = 2, hiddenQuality = 1, hiddenHoster = 3), FakeClient(), null)
            }
            val rows = Json.parseToJsonElement(client.get("/stream/movie/tt0111161.json").body<String>())
                .jsonObject["streams"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, rows.size)
            val note = rows.last()
            assertEquals(null, note["url"])
            assertEquals(true, note["name"]!!.jsonPrimitive.content.contains("loading"))
            val text = note["description"]!!.jsonPrimitive.content
            assertEquals(true, text.contains("2 sources still loading"))
            assertEquals(true, text.contains("1 hidden by your resolution filter"))
            assertEquals(true, text.contains("3 hidden by your hoster filter"))
            assertEquals(true, note["externalUrl"]!!.jsonPrimitive.content.endsWith("/configure"))
        }
    }

    @Test
    fun `the note is left out when switched off or when there is nothing to say`() {
        val streams = listOf(StremioStream("n", "d", "https://cdn.example/a.mp4"))
        testApplication {
            application { addonModule(RecordingService(streams, complete = false, loading = 2), FakeClient(), null) }
            val off = encodePrefs(Prefs(hint = false))
            assertEquals(1, Json.parseToJsonElement(client.get("/c~$off/stream/movie/tt0111161.json").body<String>()).jsonObject["streams"]!!.jsonArray.size)
        }
        testApplication {
            application { addonModule(RecordingService(streams), FakeClient(), null) }
            assertEquals(1, Json.parseToJsonElement(client.get("/stream/movie/tt0111161.json").body<String>()).jsonObject["streams"]!!.jsonArray.size)
        }
    }

    @Test
    fun `the hosters api lists names and counts only`() {
        val store = JsonFileStore(java.io.File(kotlin.io.path.createTempDirectory("hless-hosters").toString(), "s.json"), background = false)
        val stats = ProviderStats(store)
        stats.noteHoster("DoodStream"); stats.noteHoster("doodstream"); stats.noteHoster("Voe")
        testApplication {
            application { addonModule(RecordingService(), FakeClient(), null, stats = stats) }
            val list = Json.parseToJsonElement(client.get("/api/hosters").body<String>()).jsonArray.map { it.jsonObject }
            assertEquals(listOf("DoodStream", "Voe"), list.map { it["name"]!!.jsonPrimitive.content })
            assertEquals(listOf("2", "1"), list.map { it["seen"]!!.jsonPrimitive.content })
            assertEquals(setOf("name", "seen"), list.first().keys)
        }
    }

    @Test
    fun `the providers api shows how often each found something`() {
        val store = JsonFileStore(java.io.File(kotlin.io.path.createTempDirectory("hless-stats"). toString(), "s.json"), background = false)
        val stats = ProviderStats(store)
        stats.record("A", SearchOutcome.HIT); stats.record("A", SearchOutcome.MISS)
        val engine = FakeClient(providers = listOf(ProviderInfoDto(name = "A", providerId = "a", mainUrl = "https://a", language = "de", tvTypes = listOf("Movie"))))
        testApplication {
            application { addonModule(RecordingService(), engine, null, stats = stats) }
            val a = Json.parseToJsonElement(client.get("/api/providers").body<String>()).jsonArray.single().jsonObject
            assertEquals("1", a["hits"]!!.jsonPrimitive.content)
            assertEquals("2", a["tries"]!!.jsonPrimitive.content)
            assertEquals("likely", a["usefulness"]!!.jsonPrimitive.content)
        }
    }
}
