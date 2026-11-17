package com.lagradost.hless

import com.lagradost.macos.models.ProviderInfoDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The StreamService (2e + 2g): one entry per title, one coroutine per
 * provider in a shared scope, answered within the deadline with whatever is
 * ready, the rest kept for the next request.
 */
class StreamServiceImpl(
    private val client: EngineClient,
    private val titles: TitleLookup = TitleSource(),
    private val kitsuLookup: TitleLookup = KitsuLookup(),
    private val settings: RankSettings = RankSettings(
        langs = Config.langs,
        minQuality = Config.minQuality,
        maxPerProvider = Config.maxPerProvider,
        maxStreams = Config.maxStreams,
        providersDeny = Config.providersDeny,
        providersAllow = Config.providersAllow,
        providersFirst = Config.providersFirst,
    ),
    private val deadlineMs: Long = Config.deadlineMs,
) : StreamService {

    private class Entry(
        val startedAt: Long,
        val results: ConcurrentHashMap<String, List<Candidate>>,
        val job: Job,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val semaphore = Semaphore(16)

    init {
        scope.launch {
            while (true) {
                delay(60_000)
                val now = System.currentTimeMillis()
                for ((key, entry) in cache) {
                    if (now - entry.startedAt > ENTRY_TTL) {
                        cache.remove(key)
                        entry.job.cancel()
                    }
                }
            }
        }
    }

    override suspend fun streams(type: String, id: String): List<StremioStream> {
        if (id.startsWith("hless:")) return hlessStreams(id)
        val parsedId = parseStremioId(type, id) ?: return emptyList()
        if (MediaFlow.configured()) MediaFlow.wake(scope)
        val kitsu = parsedId.kitsu != null
        val effectiveType = if (type == "anime") {
            if (parsedId.episode != null) "series" else "movie"
        } else type
        val key = "$effectiveType/$id"
        val existing = cache[key]
        if (existing != null && System.currentTimeMillis() - existing.startedAt < ENTRY_TTL) {
            return settle(existing)
        }
        val info = if (kitsu) {
            kitsuLookup.lookup(effectiveType, parsedId.kitsu!!)
        } else {
            titles.lookup(effectiveType, parsedId.imdb!!)
        } ?: return emptyList()
        val providers = client.providers().filter { provider ->
            val hasType = if (kitsu) {
                provider.tvTypes.any { it in kitsuProviderTypes }
            } else {
                provider.tvTypes.any { it in wantedTypes(effectiveType) }
            }
            hasType && askProvider(provider.name, settings)
        }
        val entry = newEntry(providers, effectiveType, parsedId, info)
        val previous = cache.putIfAbsent(key, entry)
        if (previous != null && System.currentTimeMillis() - previous.startedAt < ENTRY_TTL) {
            entry.job.cancel()
            return settle(previous)
        }
        return settle(entry)
    }

    /**
     * hless ids (3b): one provider's title. No entry cache — details and
     * links run per request.
     */
    private suspend fun hlessStreams(id: String): List<StremioStream> {
        val hless = HlessId.decode(id) ?: return emptyList()
        if (MediaFlow.configured()) MediaFlow.wake(scope)
        val provider = client.providers().firstOrNull { it.providerId == hless.providerId }
            ?: return emptyList()
        if (!askProvider(provider.name, settings)) return emptyList()
        val details = client.details(hless.providerId, hless.url, timeoutMs = 20_000)
        val data = when {
            hless.episode != null -> pickEpisode(details, hless.season ?: 1, hless.episode)?.data
            details.movieData != null -> details.movieData
            details.episodes.size == 1 -> details.episodes.single().data
            else -> null
        } ?: return emptyList()
        val response = client.links(hless.providerId, data, budgetMs = 25_000)
        return response.links.mapNotNull { link ->
            StreamMapper.map(provider.name, provider.providerId, link, response.subtitles, provider.language)
        }
    }

    private fun newEntry(
        providers: List<ProviderInfoDto>,
        type: String,
        parsedId: StremioId,
        info: TitleInfo,
    ): Entry {
        val results = ConcurrentHashMap<String, List<Candidate>>()
        return Entry(
            startedAt = System.currentTimeMillis(),
            results = results,
            job = scope.launch {
                coroutineScope {
                    for (provider in providers) {
                        launch {
                            withTimeout(45_000) {
                                try {
                                    semaphore.withPermit {
                                        val data = resolveData(
                                            client, provider.providerId, type, parsedId, info, timeoutMs = 20_000,
                                        )
                                        if (data == null) return@withPermit
                                        val response = client.links(provider.providerId, data, budgetMs = 25_000)
                                        results[provider.providerId] = response.links.mapNotNull { link ->
                                            StreamMapper.map(
                                                provider.name, provider.providerId, link, response.subtitles,
                                                provider.language,
                                            )?.let { Candidate(it, provider.name, provider.language, link.quality, link.url) }
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    println("hless: ${provider.name} failed: ${e.message}")
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    /** Waits for the entry's work up to the remaining deadline, then ranks. */
    private suspend fun settle(entry: Entry): List<StremioStream> {
        val remaining = deadlineMs - (System.currentTimeMillis() - entry.startedAt)
        if (remaining > 0) {
            withTimeoutOrNull(remaining) { entry.job.join() }
        }
        return rank(entry.results.values.flatten(), settings)
    }

    companion object {
        private const val ENTRY_TTL = 5 * 60_000L
    }
}
