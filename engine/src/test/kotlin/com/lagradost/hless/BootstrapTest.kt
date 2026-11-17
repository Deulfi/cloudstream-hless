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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
class BootstrapTest {
    private fun plugin(internalName: String, installed: Boolean = false) =
        RepoPluginDto(
            id = internalName,
            name = internalName,
            internalName = internalName,
            url = "https://example.org/$internalName.cs3",
            repositoryUrl = "https://example.org/repo",
            installationId = if (installed) "inst" else null,
        )

    @Test
    fun `star selects all`() {
        assertEquals(listOf("a", "b"), choosePlugins(listOf(plugin("a"), plugin("b")), listOf("*")).map { it.internalName })
    }

    @Test
    fun `names match case-insensitively`() {
        assertEquals(listOf("MyPlugin"), choosePlugins(listOf(plugin("MyPlugin"), plugin("Other")), listOf("myplugin")).map { it.internalName })
    }

    @Test
    fun `unknown names select nothing`() {
        assertEquals(emptyList(), choosePlugins(listOf(plugin("a")), listOf("z")))
    }

    private class FakeClient(
        private val catalog: List<RepoPluginDto> = emptyList(),
        private val consentFirst: Boolean = false,
        private val fail: EngineException? = null,
    ) : EngineClient {
        val calls = mutableListOf<String>()
        var consented = false

        override suspend fun health(): HealthDto = HealthDto(uptimeMs = 0, providersLoaded = 0)
        override suspend fun awaitReady(timeoutMs: Long) {}
        override suspend fun providers(timeoutMs: Long): List<ProviderInfoDto> = emptyList()
        override suspend fun search(providerId: String, query: String, timeoutMs: Long): List<SearchResultDto> = emptyList()
        override suspend fun details(providerId: String, url: String, timeoutMs: Long) =
            DetailsDto(provider = "p", url = url, name = "n", type = "movie")
        override suspend fun home(providerId: String, page: Int, timeoutMs: Long) =
            HomePageDto(provider = "p", page = page)
        override suspend fun links(providerId: String, data: String, budgetMs: Long) =
            LinksResponseDto(provider = "p")
        override suspend fun repos(timeoutMs: Long): List<RepositoryDto> = emptyList()
        override suspend fun syncRepo(url: String, installAll: Boolean, timeoutMs: Long) =
            RepositoryDto(name = "r", url = url, status = "ok")
        override suspend fun consent(url: String, timeoutMs: Long) {
            calls += "consent"
            consented = true
        }
        override suspend fun repoPlugins(url: String, timeoutMs: Long) = catalog
        override suspend fun install(repoUrl: String, internalName: String, timeoutMs: Long): PluginDto {
            calls += "install:$internalName"
            fail?.let { throw it }
            if (consentFirst && !consented) throw EngineException(403, "consent_required", "consent first")
            return PluginDto(id = internalName, name = internalName, internalName = internalName)
        }
        override suspend fun plugins(timeoutMs: Long): List<PluginDto> = emptyList()

        override suspend fun pendingSolves(waitMs: Long): List<SolvePendingDto> = emptyList()

        override suspend fun completeSolve(taskId: String, targetUrl: String, cookies: String) {}
    }

    @Test
    fun `install grants consent when the engine requires it`() = runBlocking {
        val client = FakeClient(consentFirst = true)
        installWithConsent(client, "https://example.org/repo", "a")
        assertEquals(listOf("install:a", "consent", "install:a"), client.calls)
        assertTrue(client.consented)
    }

    @Test
    fun `other install errors do not grant consent`() = runBlocking {
        val client = FakeClient(fail = EngineException(500, "boom", "x"))
        assertFailsWith<EngineException> { installWithConsent(client, "https://example.org/repo", "a") }
        assertFalse(client.consented)
    }
}
