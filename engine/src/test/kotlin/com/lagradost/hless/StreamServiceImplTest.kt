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
    ) : EngineClient {
        val linkCalls = ConcurrentHashMap<String, Int>()

        override suspend fun health(): HealthDto = error("not used")
        override suspend fun awaitReady(timeoutMs: Long) = Unit
        override suspend fun providers(timeoutMs: Long): List<ProviderInfoDto> = providers
        override suspend fun search(providerId: String, query: String, timeoutMs: Long): List<SearchResultDto> {
            searchDelayMs[providerId]?.let { delay(it) }
            return listOf(
                SearchResultDto(name = "Test Movie", url = "https://$providerId/page", provider = providerId, type = "Movie", year = 2020),
            )
        }
        override suspend fun details(providerId: String, url: String, timeoutMs: Long): DetailsDto =
            details ?: DetailsDto(provider = providerId, url = url, name = "Test Movie", type = "Movie", movieData = "data-$providerId")
        override suspend fun home(providerId: String, page: Int, timeoutMs: Long): HomePageDto = error("not used")
        override suspend fun links(providerId: String, data: String, budgetMs: Long): LinksResponseDto {
            linkCalls.merge(providerId, 1, Int::plus)
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
    ) = StreamServiceImpl(
        client, titles = FakeTitles(), prober = NoProbe,
        deadlineMs = deadlineMs, minWaitMs = minWaitMs, quietMs = quietMs, clock = clock,
    ).also { services += it }

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
}
