package com.lagradost.hless

import com.lagradost.macos.models.DetailsDto
import com.lagradost.macos.models.EpisodeDto
import com.lagradost.macos.models.HealthDto
import com.lagradost.macos.models.HomePageDto
import com.lagradost.macos.models.LinksResponseDto
import com.lagradost.macos.models.PluginDto
import com.lagradost.macos.models.ProviderInfoDto
import com.lagradost.macos.models.RepoPluginDto
import com.lagradost.macos.models.RepositoryDto
import com.lagradost.macos.models.SearchResultDto
import com.lagradost.macos.models.SolvePendingDto
import com.lagradost.macos.models.StreamLinkDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamServiceImplTest {
    private val services = mutableListOf<StreamServiceImpl>()

    @AfterTest
    fun stop() {
        services.forEach { it.shutdown() }
    }

    private fun provider(id: String) =
        ProviderInfoDto(name = id, providerId = id, mainUrl = "https://$id.example", language = "en", tvTypes = listOf("Movie"))

    private fun link(url: String) =
        StreamLinkDto(url = url, name = "n", source = "s", quality = 1080, type = "M3U8")

    private class FakeTitles : TitleLookup {
        override suspend fun lookup(type: String, imdb: String): TitleInfo = TitleInfo(listOf("Test Movie"), 2020)
    }

    private class FakeClient(
        private val providers: List<ProviderInfoDto>,
        private val details: DetailsDto? = null,
        private val linksDelayMs: Map<String, Long> = emptyMap(),
        private val links: Map<String, List<StreamLinkDto>> = emptyMap(),
        private val fail: Set<String> = emptySet(),
        private val searchDelayMs: Map<String, Long> = emptyMap(),
        private val resultType: String = "Movie",
    ) : EngineClient {
        val linkCalls = ConcurrentHashMap<String, Int>()
        val linkData = java.util.concurrent.ConcurrentLinkedQueue<String>()
        /** (provider, nanoTime) of each search, in the order the searches began. */
        val searches = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long>>()

        override suspend fun health(): HealthDto = error("not used")
        override suspend fun awaitReady(timeoutMs: Long) = Unit
        override suspend fun providers(timeoutMs: Long): List<ProviderInfoDto> = providers
        override suspend fun search(providerId: String, query: String, timeoutMs: Long): List<SearchResultDto> {
            searches += providerId to System.nanoTime()
            searchDelayMs[providerId]?.let { delay(it) }
            return listOf(
                SearchResultDto(name = "Test Movie", url = "https://$providerId/page", provider = providerId, type = resultType, year = 2020),
            )
        }
        override suspend fun details(providerId: String, url: String, timeoutMs: Long): DetailsDto =
            details ?: DetailsDto(provider = providerId, url = url, name = "Test Movie", type = "Movie", movieData = "data-$providerId")
        override suspend fun home(providerId: String, page: Int, timeoutMs: Long): HomePageDto = error("not used")
        override suspend fun links(providerId: String, data: String, budgetMs: Long): LinksResponseDto {
            linkCalls.merge(providerId, 1, Int::plus)
            linkData += data
            linksDelayMs[providerId]?.let { delay(it) }
            if (providerId in fail) throw IllegalStateException("boom")
            return LinksResponseDto(provider = providerId, links = links[providerId] ?: emptyList())
        }
        override suspend fun repos(timeoutMs: Long): List<RepositoryDto> = emptyList()
        override suspend fun syncRepo(url: String, installAll: Boolean, timeoutMs: Long): RepositoryDto = error("not used")
        override suspend fun consent(url: String, timeoutMs: Long) = Unit
        override suspend fun repoPlugins(url: String, timeoutMs: Long): List<RepoPluginDto> = emptyList()
        override suspend fun install(repoUrl: String, internalName: String, timeoutMs: Long): PluginDto = error("not used")
        override suspend fun plugins(timeoutMs: Long): List<PluginDto> = emptyList()

        override suspend fun pendingSolves(waitMs: Long): List<SolvePendingDto> = emptyList()

        override suspend fun completeSolve(taskId: String, targetUrl: String, cookies: String) {}
    }

    private fun service(
        client: EngineClient,
        deadlineMs: Long = 5_000,
        minWaitMs: Long = 4_000,
        quietMs: Long = 3_000,
        clock: () -> Long = System::currentTimeMillis,
        stats: ProviderStats = freshStats(),
        prefetch: Boolean = false,
        gateHoldMs: Long = 8_000L,
        matches: MatchCache = freshMatches(),
    ) = StreamServiceImpl(
        client, titles = FakeTitles(), prober = NoProbe,
        deadlineMs = deadlineMs, minWaitMs = minWaitMs, quietMs = quietMs, clock = clock,
        stats = stats, prefetch = prefetch, gateHoldMs = gateHoldMs, matches = matches,
    ).also { services += it }

    private fun freshMatches() =
        MatchCache(JsonFileStore(java.io.File(kotlin.io.path.createTempDirectory("hless-matches").toString(), "m.json"), background = false))

    private fun freshStats() =
        ProviderStats(JsonFileStore(java.io.File(kotlin.io.path.createTempDirectory("hless-stats").toString(), "s.json"), background = false))

    @Test
    fun `deadline returns what is ready and later calls get the rest`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("a"), provider("b")),
            linksDelayMs = mapOf("a" to 100L, "b" to 3_000L),
            links = mapOf(
                "a" to listOf(link("https://a.example/stream")),
                "b" to listOf(link("https://b.example/stream")),
            ),
        )
        val service = service(fake, deadlineMs = 1_000)
        val first = service.streams("movie", "tt0000001", Settings())
        assertEquals(listOf("https://a.example/stream"), first.streams.map { it.url })
        assertFalse(first.complete)
        delay(3_000)
        val second = service.streams("movie", "tt0000001", Settings())
        assertEquals(2, second.streams.size)
        assertTrue(second.complete)
        assertEquals(1, fake.linkCalls["a"])
        assertEquals(1, fake.linkCalls["b"])
    }

    @Test
    fun `a provider that never finds the title does not hold the answer until the deadline`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("fast"), provider("stuck")),
            linksDelayMs = mapOf("fast" to 100L),
            searchDelayMs = mapOf("stuck" to 60_000L),
            links = mapOf("fast" to listOf(link("https://fast.example/stream"))),
        )
        val service = service(fake, deadlineMs = 20_000, minWaitMs = 300, quietMs = 300)
        val started = System.currentTimeMillis()
        val result = service.streams("movie", "tt0000003", Settings())
        val took = System.currentTimeMillis() - started
        assertEquals(listOf("https://fast.example/stream"), result.streams.map { it.url })
        assertFalse(result.complete)
        assertTrue(took < 5_000, "answered after $took ms")
    }

    @Test
    fun `a provider that has found the title is waited for even after the quiet period`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("fast"), provider("slow"), provider("stuck")),
            linksDelayMs = mapOf("fast" to 100L, "slow" to 2_500L),
            searchDelayMs = mapOf("stuck" to 60_000L),
            links = mapOf(
                "fast" to listOf(link("https://fast.example/stream")),
                "slow" to listOf(link("https://slow.example/stream")),
            ),
        )
        val service = service(fake, deadlineMs = 20_000, minWaitMs = 300, quietMs = 300)
        val result = service.streams("movie", "tt0000009", Settings())
        assertEquals(setOf("https://fast.example/stream", "https://slow.example/stream"), result.streams.map { it.url }.toSet())
        assertFalse(result.complete)   // the stuck one is still searching
    }

    @Test
    fun `a search that waits for a blocked site gives up its place after a while`() = runBlocking {
        val stuck = (1..12).map { provider("stuck$it") }
        val fake = FakeClient(
            providers = stuck + provider("fast"),
            searchDelayMs = stuck.associate { it.providerId to 60_000L },
            links = mapOf("fast" to listOf(link("https://fast.example/stream"))),
        )
        val service = service(fake, deadlineMs = 10_000, minWaitMs = 300, quietMs = 300, gateHoldMs = 300)
        val result = service.streams("movie", "tt0000010", Settings())
        assertEquals(listOf("https://fast.example/stream"), result.streams.map { it.url })
    }

    @Test
    fun `new titles are refused while too many providers are running`() = runBlocking {
        val many = (1..160).map { provider("p$it") }
        val fake = FakeClient(
            providers = many,
            searchDelayMs = many.associate { it.providerId to 60_000L },
        )
        val service = service(fake, deadlineMs = 300, minWaitMs = 100, quietMs = 100)
        service.streams("movie", "tt0000011", Settings())            // starts 160 tasks that stay running
        val refused = service.streams("movie", "tt0000012", Settings())
        assertEquals(0, refused.streams.size)
        assertFalse(refused.complete)
        assertEquals(null, fake.linkCalls["p1"])
    }

    @Test
    fun `when every provider is done the answer is complete and immediate`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("a"), provider("b")),
            links = mapOf("a" to listOf(link("https://a.example/stream")), "b" to listOf(link("https://b.example/stream"))),
        )
        val service = service(fake)
        val started = System.currentTimeMillis()
        val result = service.streams("movie", "tt0000004", Settings())
        assertTrue(System.currentTimeMillis() - started < 3_000)
        assertTrue(result.complete)
        assertEquals(2, result.streams.size)
    }

    @Test
    fun `nothing finishing answers at the deadline with an empty list`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("stuck")),
            linksDelayMs = mapOf("stuck" to 60_000L),
        )
        val service = service(fake, deadlineMs = 600, minWaitMs = 100, quietMs = 100)
        val result = service.streams("movie", "tt0000005", Settings())
        assertEquals(0, result.streams.size)
        assertFalse(result.complete)
    }

    @Test
    fun `a failing provider does not sink the others`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("a"), provider("c")),
            links = mapOf("a" to listOf(link("https://a.example/stream"))),
            fail = setOf("c"),
        )
        val result = service(fake).streams("movie", "tt0000002", Settings())
        assertEquals(listOf("https://a.example/stream"), result.streams.map { it.url })
        assertTrue(result.complete)
    }

    @Test
    fun `requests with different settings share the provider work`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("a"), provider("b")),
            links = mapOf("a" to listOf(link("https://a.example/stream")), "b" to listOf(link("https://b.example/stream"))),
        )
        val service = service(fake)
        val all = service.streams("movie", "tt0000006", Settings())
        assertEquals(2, all.streams.size)
        val withoutB = service.streams("movie", "tt0000006", Settings(rank = RankSettings(providersDeny = listOf("b"))))
        assertEquals(listOf("https://a.example/stream"), withoutB.streams.map { it.url })
        val again = service.streams("movie", "tt0000006", Settings())
        assertEquals(2, again.streams.size)
        assertEquals(1, fake.linkCalls["a"])
        assertEquals(1, fake.linkCalls["b"])
    }

    @Test
    fun `an empty task is asked again after thirty seconds, one with streams after five minutes`() = runBlocking {
        val now = AtomicLong(1_000_000L)
        val fake = FakeClient(
            providers = listOf(provider("empty"), provider("full")),
            links = mapOf("full" to listOf(link("https://full.example/stream"))),
        )
        val service = service(fake, clock = { now.get() })
        service.streams("movie", "tt0000007", Settings())
        assertEquals(1, fake.linkCalls["empty"])
        now.addAndGet(31_000L)
        service.streams("movie", "tt0000007", Settings())
        assertEquals(2, fake.linkCalls["empty"])
        assertEquals(1, fake.linkCalls["full"])
        now.addAndGet(5 * 60_000L)
        service.streams("movie", "tt0000007", Settings())
        assertEquals(2, fake.linkCalls["full"])
    }

    @Test
    fun `a probe fills in the facts and the display shows them`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("a")),
            links = mapOf("a" to listOf(StreamLinkDto(url = "https://a.example/f.mp4", name = "n", source = "Host", quality = 400, type = "VIDEO"))),
        )
        val prober = object : Prober {
            override suspend fun probe(link: StreamLinkDto): LinkFacts = LinkFacts(Kind.FILE, sizeBytes = 1_010_000_000L)
        }
        val service = StreamServiceImpl(
            fake, titles = FakeTitles(), prober = prober, deadlineMs = 5_000, minWaitMs = 4_000, quietMs = 3_000,
        ).also { services += it }
        val streams = service.streams("movie", "tt0000008", Settings()).streams
        assertEquals(1, streams.size)
        assertTrue("💾 1.01 GB" in streams[0].description, streams[0].description)
        assertEquals(1_010_000_000L, streams[0].behaviorHints?.videoSize)
    }

    @Test
    fun `hless ids go through a direct provider lookup and details call`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("a")),
            details = DetailsDto(
                provider = "a",
                url = "https://a/m1",
                name = "A Movie",
                type = "Movie",
                movieData = "data",
                episodes = listOf(EpisodeDto(data = "e1", name = "E1", season = 1, episode = 1)),
            ),
            links = mapOf("a" to listOf(link("https://a.stream"))),
        )
        val result = service(fake).streams("movie", HlessId("a", "https://a/m1").encode(), Settings())
        assertEquals(1, result.streams.size)
        assertEquals("https://a.stream", result.streams[0].url)
        assertTrue("A Movie" in result.streams[0].description)
    }

    // ---- order, relevance, prefetch (the first answer should hold what matters)

    private fun settle(stats: ProviderStats, name: String, outcome: SearchOutcome, times: Int = 1) =
        repeat(times) { stats.record(name, outcome) }

    @Test
    fun `providers that usually find something search first, the ones that never do search last`() = runBlocking {
        val stats = freshStats()
        settle(stats, "likely1", SearchOutcome.HIT)
        settle(stats, "likely2", SearchOutcome.HIT)
        settle(stats, "dormant1", SearchOutcome.FAIL, 10)
        settle(stats, "dormant2", SearchOutcome.MISS, 10)
        val names = listOf("dormant1", "dormant2", "new1", "new2", "new3", "new4", "likely1", "likely2")
        val fake = FakeClient(
            providers = names.map { provider(it) },
            searchDelayMs = names.associateWith { 400L },
            links = names.associateWith { listOf(link("https://$it.example/stream")) },
        )
        val service = service(fake, deadlineMs = 10_000, stats = stats)
        service.streams("movie", "tt0000030", Settings())
        delay(900)   // the dormant ones start once the others are through their search
        val begun = fake.searches.toList()
        assertEquals(setOf("likely1", "likely2"), begun.take(2).map { it.first }.toSet())
        // six places: the two likely and four new ones start at once; the dormant two wait for a place
        val firstBatch = begun.take(6).map { it.first }.toSet()
        assertEquals(setOf("likely1", "likely2", "new1", "new2", "new3", "new4"), firstBatch)
        val late = begun.drop(6)
        assertEquals(setOf("dormant1", "dormant2"), late.map { it.first }.toSet())
        assertTrue((late.minOf { it.second } - begun.first().second) / 1_000_000 >= 300, "the dormant ones started too early")
    }

    @Test
    fun `the dormant providers search only after the useful ones are through their search`() = runBlocking {
        val stats = freshStats()
        settle(stats, "ghost", SearchOutcome.FAIL, 10)
        val fake = FakeClient(
            providers = listOf(provider("ghost"), provider("useful")),
            searchDelayMs = mapOf("useful" to 500L),
        )
        val service = service(fake, deadlineMs = 5_000, minWaitMs = 100, quietMs = 100, stats = stats)
        service.streams("movie", "tt0000036", Settings())
        delay(300)
        val begun = fake.searches.toList()
        val useful = begun.first { it.first == "useful" }.second
        val ghost = begun.firstOrNull { it.first == "ghost" }?.second
        assertEquals(true, ghost == null || (ghost - useful) / 1_000_000 >= 450, "the dormant provider searched too early")
    }

    @Test
    fun `a provider whose site the solver gave up on is not asked while that lasts`() = runBlocking {
        BlockedHosts.clear()
        BlockedHosts.block("walled.example", 60_000)
        try {
            val fake = FakeClient(
                providers = listOf(provider("walled"), provider("open")),
                links = mapOf("open" to listOf(link("https://open.example/stream"))),
            )
            val result = service(fake).streams("movie", "tt0000037", Settings())
            assertEquals(listOf("https://open.example/stream"), result.streams.map { it.url })
            assertEquals(listOf("open"), fake.searches.toList().map { it.first })
        } finally {
            BlockedHosts.clear()
        }
    }

    @Test
    fun `without the smart order the providers search exactly in the order given`() = runBlocking {
        val stats = freshStats()
        settle(stats, "p8", SearchOutcome.HIT)                          // would come first if smart
        val names = (1..8).map { "p$it" }
        val fake = FakeClient(providers = names.map { provider(it) }, searchDelayMs = names.associateWith { 300L })
        val service = service(fake, deadlineMs = 5_000, minWaitMs = 100, quietMs = 100, stats = stats)
        val order = listOf("p3", "p1", "p2", "p8", "p7", "p6", "p5", "p4")
        service.streams("movie", "tt0000031", Settings(rank = RankSettings(order = order, smart = false)))
        assertEquals(order.take(6), fake.searches.toList().take(6).map { it.first })
    }

    @Test
    fun `the dormant providers do not hold the answer`() = runBlocking {
        val stats = freshStats()
        settle(stats, "ghost", SearchOutcome.FAIL, 10)
        val fake = FakeClient(
            providers = listOf(provider("ghost"), provider("fast")),
            searchDelayMs = mapOf("ghost" to 60_000L),
            links = mapOf("fast" to listOf(link("https://fast.example/stream"))),
        )
        val service = service(fake, deadlineMs = 20_000, minWaitMs = 10_000, quietMs = 10_000, stats = stats)
        val started = System.currentTimeMillis()
        val result = service.streams("movie", "tt0000032", Settings())
        assertEquals(listOf("https://fast.example/stream"), result.streams.map { it.url })
        assertFalse(result.complete)
        assertEquals(0, result.loading)          // nothing worth waiting for is left
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun `a provider that usually delivers is waited for even when the others have gone quiet`() = runBlocking {
        val stats = freshStats()
        settle(stats, "steady", SearchOutcome.HIT)
        val fake = FakeClient(
            providers = listOf(provider("steady"), provider("quick")),
            searchDelayMs = mapOf("steady" to 2_500L),
            links = mapOf("steady" to listOf(link("https://steady.example/stream")), "quick" to listOf(link("https://quick.example/stream"))),
        )
        val service = service(fake, deadlineMs = 20_000, minWaitMs = 300, quietMs = 300, stats = stats)
        val result = service.streams("movie", "tt0000033", Settings())
        assertEquals(setOf("https://steady.example/stream", "https://quick.example/stream"), result.streams.map { it.url }.toSet())
    }

    @Test
    fun `a partial answer says how many providers are still loading, and what a filter hid`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("slow"), provider("quick")),
            linksDelayMs = mapOf("slow" to 60_000L),
            links = mapOf(
                "quick" to listOf(
                    StreamLinkDto(url = "https://quick.example/low", name = "n", source = "Voe", quality = 480, type = "VIDEO"),
                    StreamLinkDto(url = "https://quick.example/dood", name = "n", source = "DoodStream", quality = 1080, type = "VIDEO"),
                ),
            ),
        )
        val service = service(fake, deadlineMs = 1_500, minWaitMs = 10_000, stats = freshStats())
        val settings = Settings(rank = RankSettings(minQuality = 720, hostersOff = listOf("doodstream")))
        val result = service.streams("movie", "tt0000034", settings)
        assertEquals(0, result.streams.size)
        assertFalse(result.complete)
        assertEquals(1, result.loading)
        assertEquals(1, result.hiddenQuality)
        assertEquals(1, result.hiddenHoster)
    }

    @Test
    fun `after an episode the providers that delivered it start on the next one`() = runBlocking {
        val episodes = DetailsDto(
            provider = "a", url = "u", name = "Show", type = "TvSeries",
            episodes = listOf(EpisodeDto(data = "ep1", season = 1, episode = 1), EpisodeDto(data = "ep2", season = 1, episode = 2)),
        )
        val series = ProviderInfoDto(name = "a", providerId = "a", mainUrl = "https://a.example", language = "en", tvTypes = listOf("TvSeries"))
        val fake = FakeClient(
            providers = listOf(series), details = episodes, resultType = "TvSeries",
            links = mapOf("a" to listOf(link("https://a.example/stream"))),
        )
        val service = service(fake, prefetch = true)
        service.streams("series", "tt0000035:1:1", Settings())
        delay(1_500)
        assertEquals(listOf("ep1", "ep2"), fake.linkData.toList())
        // opening the next episode now finds it ready: no new link call
        val next = service.streams("series", "tt0000035:1:2", Settings())
        assertEquals(1, next.streams.size)
        assertEquals(2, fake.linkData.size)
    }
}
