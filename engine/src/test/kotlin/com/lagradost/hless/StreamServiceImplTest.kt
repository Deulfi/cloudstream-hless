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
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamServiceImplTest {
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
    ) : EngineClient {
        val linkCalls = ConcurrentHashMap<String, Int>()

        override suspend fun health(): HealthDto = error("not used")
        override suspend fun awaitReady(timeoutMs: Long) = Unit
        override suspend fun providers(timeoutMs: Long): List<ProviderInfoDto> = providers
        override suspend fun search(providerId: String, query: String, timeoutMs: Long): List<SearchResultDto> =
            listOf(
                SearchResultDto(name = "Test Movie", url = "https://$providerId/page", provider = providerId, type = "Movie", year = 2020),
            )
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
        val service = StreamServiceImpl(fake, titles = FakeTitles(), settings = RankSettings(), deadlineMs = 1_000)
        val first = service.streams("movie", "tt0000001")
        assertEquals(listOf("https://a.example/stream"), first.map { it.url })
        delay(3_000)
        val second = service.streams("movie", "tt0000001")
        assertEquals(2, second.size)
        assertEquals(1, fake.linkCalls["a"])
        assertEquals(1, fake.linkCalls["b"])
    }

    @Test
    fun `a failing provider does not sink the others`() = runBlocking {
        val fake = FakeClient(
            providers = listOf(provider("a"), provider("c")),
            links = mapOf("a" to listOf(link("https://a.example/stream"))),
            fail = setOf("c"),
        )
        val service = StreamServiceImpl(fake, titles = FakeTitles(), settings = RankSettings(), deadlineMs = 5_000)
        val streams = service.streams("movie", "tt0000002")
        assertEquals(listOf("https://a.example/stream"), streams.map { it.url })
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
        val service = StreamServiceImpl(fake, titles = FakeTitles(), settings = RankSettings(), deadlineMs = 5_000)
        val streams = service.streams("movie", HlessId("a", "https://a/m1").encode())
        assertEquals(1, streams.size)
        assertEquals("https://a.stream", streams[0].url)
    }
}
