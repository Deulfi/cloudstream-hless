package com.lagradost.hless

import com.lagradost.macos.models.ProviderInfoDto
import com.lagradost.macos.models.StreamLinkDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The StreamService (2e, 5b, 5c, 6a).
 *
 * The unit of work is one provider for one title: it is started once, kept
 * for a while, and shared by every request that wants it, whatever that
 * request's settings are. A request asks for the providers its settings
 * allow, waits for them (but not for stuck ones, see [settle]), and renders
 * the answer with its own settings. Providers still running when a request
 * answers keep going and are there for the next one.
 */
class StreamServiceImpl(
    private val client: EngineClient,
    private val titles: TitleLookup = TitleSource(),
    private val kitsuLookup: TitleLookup = KitsuLookup(),
    private val prober: Prober = LinkProbe(),
    private val deadlineMs: Long = Config.deadlineMs,
    private val minWaitMs: Long = Config.minWaitMs,
    private val quietMs: Long = Config.quietMs,
    private val clock: () -> Long = System::currentTimeMillis,
) : StreamService {

    /** One provider's work for one title. */
    private class ProviderTask(val provider: ProviderRef, startedAt: Long) {
        /** The latest published links (first without facts, then probed). */
        @Volatile var result: ProviderResult? = null
        @Volatile var lastActivityAt: Long = startedAt
        /** The provider has the title and is fetching or probing its links: worth waiting for. */
        @Volatile var matched: Boolean = false
        /** search, links, probe or done; for the log only. */
        @Volatile var phase: String = "search"
        /** 0 while running. */
        @Volatile var finishedAt: Long = 0L
        @Volatile var job: Job? = null
        val hasLinks: Boolean get() = result?.items?.isNotEmpty() == true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tasks = ConcurrentHashMap<String, ProviderTask>()
    private val semaphore = Semaphore(16)

    init {
        scope.launch {
            while (true) {
                delay(60_000)
                val now = clock()
                for ((key, task) in tasks) {
                    if (!isFresh(task, now)) {
                        tasks.remove(key, task)
                        task.job?.cancel()
                    }
                }
            }
        }
    }

    /** Stops all provider work; for tests and for a clean exit. */
    fun shutdown() {
        scope.cancel()
    }

    override suspend fun streams(type: String, id: String, settings: Settings): StreamResult {
        val started = clock()
        if (id.startsWith("hless:")) return hlessStreams(id, settings)
        val parsedId = parseStremioId(type, id) ?: return StreamResult(emptyList(), true)
        if (settings.proxy && MediaFlow.configured()) MediaFlow.wake(scope)
        val kitsu = parsedId.kitsu != null
        val effectiveType = if (type == "anime") {
            if (parsedId.episode != null) "series" else "movie"
        } else type
        val info = (if (kitsu) {
            kitsuLookup.lookup(effectiveType, parsedId.kitsu!!)
        } else {
            titles.lookup(effectiveType, parsedId.imdb!!)
        }) ?: return StreamResult(emptyList(), false)
        val providers = client.providers().filter { provider ->
            val hasType = if (kitsu) {
                provider.tvTypes.any { it in kitsuProviderTypes }
            } else {
                provider.tvTypes.any { it in wantedTypes(effectiveType) }
            }
            hasType && askProvider(provider.name, settings.rank)
        }
        val wanted = providers.map { taskFor("$effectiveType/$id", it, effectiveType, parsedId, info) }
        val complete = settle(wanted, started)
        val results = wanted.mapNotNull { it.result }
        val rendered = renderStreams(
            results, titleLine(info, parsedId), info.runtimeMin, settings, StreamMapper.defaultMediaflow,
        )
        println(
            "hless: $id $effectiveType: ${results.size}/${providers.size} providers answered, " +
                "${rendered.links} links, ${rendered.streams.size} shown, dropped ${rendered.dropped} " +
                "in ${String.format(java.util.Locale.ROOT, "%.1f", (clock() - started) / 1000.0)}s " +
                if (complete) "(complete)" else "(partial, working: ${stillWorking(wanted)})",
        )
        return StreamResult(rendered.streams, complete)
    }

    /**
     * hless ids (3b): one provider's title. No task cache: details and links
     * run per request.
     */
    private suspend fun hlessStreams(id: String, settings: Settings): StreamResult {
        val hless = HlessId.decode(id) ?: return StreamResult(emptyList(), true)
        if (settings.proxy && MediaFlow.configured()) MediaFlow.wake(scope)
        val provider = client.providers().firstOrNull { it.providerId == hless.providerId }
            ?: return StreamResult(emptyList(), true)
        if (!askProvider(provider.name, settings.rank)) return StreamResult(emptyList(), true)
        val details = client.details(hless.providerId, hless.url, timeoutMs = 20_000)
        val data = when {
            hless.episode != null -> pickEpisode(details, hless.season ?: 1, hless.episode)?.data
            details.movieData != null -> details.movieData
            details.episodes.size == 1 -> details.episodes.single().data
            else -> null
        } ?: return StreamResult(emptyList(), true)
        val response = client.links(hless.providerId, data, budgetMs = 25_000)
        val items = probeAll(response.links)
        val ref = ProviderRef(provider.name, provider.providerId, provider.language)
        val title = if (hless.episode != null) "${details.name} ${episodeTag(hless.season, hless.episode)}" else details.name
        val rendered = renderStreams(
            listOf(ProviderResult(ref, items, response.subtitles)), title, null, settings, StreamMapper.defaultMediaflow,
        )
        return StreamResult(rendered.streams, true)
    }

    private fun isFresh(task: ProviderTask, now: Long): Boolean {
        if (task.finishedAt == 0L) return true
        val ttl = if (task.hasLinks) TASK_TTL else EMPTY_TTL
        return now - task.finishedAt < ttl
    }

    /** The running or finished task for this provider and title, or a new one. */
    private fun taskFor(
        titleKey: String,
        provider: ProviderInfoDto,
        type: String,
        parsedId: StremioId,
        info: TitleInfo,
    ): ProviderTask {
        val key = "$titleKey|${provider.providerId}"
        val now = clock()
        val fresh = ProviderTask(ProviderRef(provider.name, provider.providerId, provider.language), now)
        val chosen = tasks.compute(key) { _, current -> if (current != null && isFresh(current, now)) current else fresh }!!
        if (chosen === fresh) {
            fresh.job = scope.launch { runProvider(fresh, provider, type, parsedId, info) }
        }
        return chosen
    }

    private suspend fun runProvider(
        task: ProviderTask,
        provider: ProviderInfoDto,
        type: String,
        parsedId: StremioId,
        info: TitleInfo,
    ) {
        try {
            withTimeout(PROVIDER_TIMEOUT_MS) {
                val response = semaphore.withPermit {
                    val data = resolveData(client, provider.providerId, type, parsedId, info, timeoutMs = 20_000)
                        ?: return@withPermit null
                    task.matched = true
                    task.phase = "links"
                    task.lastActivityAt = clock()
                    client.links(provider.providerId, data, budgetMs = 25_000)
                } ?: return@withTimeout
                // Publish the links at once, so a slow probe never costs a stream at the deadline.
                task.phase = "probe"
                publish(task, response.links.map { Item(it) }, response)
                publish(task, probeAll(response.links), response)
            }
        } catch (e: TimeoutCancellationException) {
            println("hless: ${provider.name} timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? EngineException)?.code
            println("hless: ${provider.name} failed: ${e.javaClass.simpleName}${code?.let { " ($it)" }.orEmpty()}")
        } finally {
            task.phase = "done"
            task.finishedAt = clock()
            task.lastActivityAt = task.finishedAt
        }
    }

    private fun publish(task: ProviderTask, items: List<Item>, response: com.lagradost.macos.models.LinksResponseDto) {
        task.result = ProviderResult(task.provider, items, response.subtitles)
        task.lastActivityAt = clock()
    }

    /** Probes every link at once; a probe that takes longer than [PROBE_BUDGET_MS] (queueing included) is given up on. */
    private suspend fun probeAll(links: List<StreamLinkDto>): List<Item> = coroutineScope {
        links.map { link -> async { Item(link, withTimeoutOrNull(PROBE_BUDGET_MS) { prober.probe(link) }) } }.awaitAll()
    }

    /**
     * Waits for [wanted] but not for providers that are clearly stuck. Answers when
     *  1. every provider is done: complete; or
     *  2. the deadline passed; or
     *  3. the minimum wait passed, at least one provider has links, no provider
     *     that already found the title is still fetching its links, and nothing
     *     has happened for the quiet period.
     * A provider that has not found the title after that long (a blocked site,
     * a news channel searched for a film) is not worth waiting for; one that
     * has found it is. The providers still running are not cancelled; they
     * fill the cache for the next request. Returns whether the answer is
     * complete.
     */
    private suspend fun settle(wanted: List<ProviderTask>, started: Long): Boolean {
        while (true) {
            if (wanted.all { it.finishedAt != 0L }) return true
            val now = clock()
            val elapsed = now - started
            if (elapsed >= deadlineMs) return false
            val working = wanted.any { it.matched && it.finishedAt == 0L }
            if (!working && elapsed >= minWaitMs && wanted.any { it.hasLinks } &&
                now - wanted.maxOf { it.lastActivityAt } >= quietMs
            ) return false
            delay(POLL_MS)
        }
    }

    /** Provider names and phases of the tasks that did not finish, for the log; at most eight, matched ones first. */
    private fun stillWorking(wanted: List<ProviderTask>): String {
        val running = wanted.filter { it.finishedAt == 0L }.sortedByDescending { it.matched }
        val shown = running.take(8).joinToString(",") { "${it.provider.name}:${it.phase}" }
        return if (running.size > 8) "$shown,+${running.size - 8}" else shown
    }

    private fun titleLine(info: TitleInfo, id: StremioId): String {
        val name = info.names.firstOrNull().orEmpty()
        return when {
            id.episode != null -> "$name ${episodeTag(id.season, id.episode)}"
            info.year != null -> "$name (${info.year})"
            else -> name
        }
    }

    private fun episodeTag(season: Int?, episode: Int): String =
        if (season != null) String.format(java.util.Locale.ROOT, "S%02dE%02d", season, episode)
        else String.format(java.util.Locale.ROOT, "E%02d", episode)

    companion object {
        private const val TASK_TTL = 5 * 60_000L
        private const val EMPTY_TTL = 30_000L
        private const val PROVIDER_TIMEOUT_MS = 45_000L
        private const val PROBE_BUDGET_MS = 4_000L
        private const val POLL_MS = 100L
    }
}
