package com.lagradost.hless

import com.lagradost.macos.models.DetailsDto
import com.lagradost.macos.models.HealthDto
import com.lagradost.macos.models.HomePageDto
import com.lagradost.macos.models.LinksResponseDto
import com.lagradost.macos.models.PluginDto
import com.lagradost.macos.models.ProviderInfoDto
import com.lagradost.macos.models.RepoPluginDto
import com.lagradost.macos.models.RepositoryDto
import com.lagradost.macos.models.SearchResultDto
import com.lagradost.macos.models.SolvePendingDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolverTest {
    private class SearchClient(
        private val results: List<SearchResultDto>,
        private val searchMs: Long = 0,
    ) : EngineClient {
        val searches = AtomicInteger(0)

        override suspend fun health(): HealthDto = error("not used")
        override suspend fun awaitReady(timeoutMs: Long) = Unit
        override suspend fun providers(timeoutMs: Long): List<ProviderInfoDto> = emptyList()
        override suspend fun search(providerId: String, query: String, timeoutMs: Long): List<SearchResultDto> {
            searches.incrementAndGet()
            if (searchMs > 0) delay(searchMs)
            return results
        }
        override suspend fun details(providerId: String, url: String, timeoutMs: Long) =
            DetailsDto(provider = providerId, url = url, name = "n", type = "Movie", movieData = "data")
        override suspend fun home(providerId: String, page: Int, timeoutMs: Long): HomePageDto = error("not used")
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

    private fun cache() = MatchCache(JsonFileStore(File(createTempDirectory("hless-resolver").toString(), "m.json"), background = false))

    private val info = TitleInfo(listOf("Some Movie"), 2020)
    private val id = StremioId("tt1", null, null, null)

    @Test
    fun `a quick empty answer is remembered as not there`() = runBlocking {
        val client = SearchClient(emptyList())
        val cache = cache()
        assertNull(resolveData(client, "p", "movie", id, info, 5_000, cache))
        assertNull(resolveData(client, "p", "movie", id, info, 5_000, cache))
        assertEquals(1, client.searches.get())   // the name is searched once, then the miss is used
    }

    @Test
    fun `a slow empty answer probably failed and is not remembered`() = runBlocking {
        val client = SearchClient(emptyList(), searchMs = 3_200)
        val cache = cache()
        assertNull(resolveData(client, "p", "movie", id, info.copy(names = listOf("A", "B")), 10_000, cache))
        assertEquals(MatchCache.Entry.Absent, cache.lookup("tt1", "p"))
    }

    @Test
    fun `a found title is remembered`() = runBlocking {
        val hit = SearchResultDto(name = "Some Movie", url = "https://p/page", provider = "p", type = "Movie", year = 2020)
        val client = SearchClient(listOf(hit))
        val cache = cache()
        assertEquals("data", resolveData(client, "p", "movie", id, info, 5_000, cache))
        assertEquals("data", resolveData(client, "p", "movie", id, info, 5_000, cache))
        assertEquals(1, client.searches.get())
    }
}
