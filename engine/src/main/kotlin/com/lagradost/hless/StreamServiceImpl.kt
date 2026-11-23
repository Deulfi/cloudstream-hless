package com.lagradost.hless

import com.lagradost.macos.models.LinksResponseDto
import com.lagradost.macos.models.ProviderInfoDto
import com.lagradost.macos.models.StreamLinkDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The StreamService (2e, 5b, 5c, 6a).
 *
 * The unit of work is one provider for one title: it is started once, kept
 * for a while, and shared by every request that wants it, whatever that
 * request's settings are. A request asks for the providers its settings
 * allow, in the order that gets the useful ones answered first (see [order]),
 * waits for the ones that matter (see [settle]), and renders the answer with
 * its own settings. Providers still running when a request answers keep going
 * and are there for the next one.
 */
class StreamServiceImpl(
    private val client: EngineClient,
    private val titles: TitleLookup = TitleSource(),
    private val kitsuLookup: TitleLookup = KitsuLookup(),
    private val prober: Prober = LinkProbe(),
    private val deadlineMs: Long = Config.deadlineMs,
    /** After the deadline, how much longer to wait while there is nothing at all to show. */
    private val emptyWaitMs: Long = Config.emptyWaitMs,
    private val minWaitMs: Long = Config.minWaitMs,
    private val quietMs: Long = Config.quietMs,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stats: ProviderStats = ProviderStats(),
    private val prefetch: Boolean = true,
    /** How long a search may keep its place in the search gate; a provider that waits for a blocked site must not hold it for a minute. */
    private val gateHoldMs: Long = 8_000L,
    /** Which page of which provider belongs to a title; one per process, tests bring their own. */
    private val matches: MatchCache = MatchCache(HlessStores.matchStore),
    /** Seals the server's MediaFlow URLs so no player sees the password; null when off. */
    private val sealer: MediaFlowSealer? = MediaFlowSealer.fromConfig(),
) : StreamService {

    /** One provider's work for one title. */
    private class ProviderTask(
        val provider: ProviderRef,
        val info: ProviderInfoDto,
        val usefulness: Usefulness,
        startedAt: Long,
        /** Tasks whose search must be over before this one searches (the dormant providers wait for the useful ones). */
        val startAfter: List<ProviderTask> = emptyList(),
    ) {
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

    /** Link extraction, the heavy part. */
    private val semaphore = Semaphore(16)

    /**
     * Title searches. The engine runs 8 searches at once and queues the rest in no
     * useful order; asking for 6 here keeps its queue empty, so the order in which
     * the providers were started is the order in which they search (the semaphore
     * is fair).
     */
    private val searchGate = Semaphore(SEARCH_GATE)

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
        if (tasks.values.count { it.finishedAt == 0L } >= MAX_RUNNING) {
            // Every distinct title starts work at every provider; without a bound a client
            // asking for thousands of ids would exhaust memory and hammer the sites.
            RateLimitedLog.log("overload", "hless: too many providers running, new titles are refused for now", 60_000L)
            return StreamResult(emptyList(), false)
        }
        // only the server's own MediaFlow is woken: never an address a person put in their link
        if (settings.proxy && settings.ownProxy == null && MediaFlow.configured()) MediaFlow.wake(scope)
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
            hasType && askProvider(provider.name, settings.rank) && !BlockedHosts.isBlocked(hostOf(provider.mainUrl))
        }
        val titleKey = "$effectiveType/$id"
        val wanted = ArrayList<ProviderTask>()
        for ((provider, usefulness) in order(providers, settings.rank)) {
            // sorted: the dormant providers come last and wait for all the others to be through their search
            val before = if (usefulness == Usefulness.DORMANT) wanted.filter { it.usefulness != Usefulness.DORMANT } else emptyList()
            wanted += taskFor(titleKey, provider, usefulness, effectiveType, parsedId, info, before)
        }
        evictOldFinished()
        val relevant = relevantOf(wanted)
        val complete = settle(wanted, relevant, started)
        val results = wanted.mapNotNull { it.result }
        val rendered = renderStreams(
            results, titleLine(info, parsedId), info.runtimeMin, settings, StreamMapper.defaultMediaflow,
        )
        val loading = if (complete) 0 else relevant.count { it.finishedAt == 0L }
        println(
            "hless: $id $effectiveType: ${results.size}/${providers.size} providers answered, " +
                "${rendered.links} links, ${rendered.streams.size} shown, dropped ${rendered.dropped} " +
                "in ${String.format(java.util.Locale.ROOT, "%.1f", (clock() - started) / 1000.0)}s " +
                if (complete) "(complete)" else "(partial, working: ${stillWorking(wanted)})",
        )
        if (prefetch && settings.rank.smart) prefetchNext(effectiveType, parsedId, info, wanted)
        return StreamResult(
            sealed(rendered.streams, settings), complete, loading,
            hiddenQuality = (rendered.dropped[DropReason.BELOW_MIN_QUALITY] ?: 0) + (rendered.dropped[DropReason.ABOVE_MAX_QUALITY] ?: 0),
            hiddenHoster = rendered.dropped[DropReason.HOSTER_OFF] ?: 0,
        )
    }

    /**
     * The order in which providers are started, which is the order in which they search:
     * smart (default) puts the ones that usually find something first, then the unknown
     * ones, then the ones that never did; within a class, those with more hits come first and
     * then the person's order. Not smart: exactly the person's order (then the engine's).
     */
    private fun order(providers: List<ProviderInfoDto>, rank: RankSettings): List<Pair<ProviderInfoDto, Usefulness>> {
        val page = rank.order.withIndex().associate { it.value.lowercase() to it.index }
        fun position(provider: ProviderInfoDto) = page[provider.name.lowercase()] ?: Int.MAX_VALUE
        val langs = rank.langs.map { it.lowercase() }.toSet()
        val classified = providers.map { p ->
            var u = if (rank.smart) stats.classify(p.name) else Usefulness.UNKNOWN
            // A provider in a language the person did not pick goes last, unless it has delivered lately.
            if (rank.smart && langs.isNotEmpty() && u != Usefulness.LIKELY && p.language.lowercase() !in langs) u = Usefulness.DORMANT
            p to u
        }
        return classified.sortedWith(
            compareBy<Pair<ProviderInfoDto, Usefulness>>(
                { it.second.ordinal },
                { if (rank.smart) -stats.counts(it.first.name).hits else 0 },
                { position(it.first) },
            ),
        )
    }

    /** The tasks worth waiting for: all but the dormant ones (unless nothing else is left). */
    private fun relevantOf(wanted: List<ProviderTask>): List<ProviderTask> =
        wanted.filter { it.usefulness != Usefulness.DORMANT }.ifEmpty { wanted }

    /**
     * hless ids (3b): one provider's title. No task cache: details and links
     * run per request.
     */
    private suspend fun hlessStreams(id: String, settings: Settings): StreamResult {
        val hless = HlessId.decode(id) ?: return StreamResult(emptyList(), true)
        // only the server's own MediaFlow is woken: never an address a person put in their link
        if (settings.proxy && settings.ownProxy == null && MediaFlow.configured()) MediaFlow.wake(scope)
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
        return StreamResult(sealed(rendered.streams, settings), true)
    }

    /** The streams with the server's MediaFlow URLs sealed (a person's own MediaFlow is left alone). */
    private suspend fun sealed(streams: List<StremioStream>, settings: Settings): List<StremioStream> {
        val s = sealer ?: return streams
        if (!settings.proxy || settings.ownProxy != null) return streams
        return s.sealAll(streams)
    }

    /**
     * After an episode: the providers that just delivered it start on the next one, so that
     * its streams are usually ready when it is opened. Only those providers (the match cache
     * already knows their page, so it costs details and links), and not under load.
     */
    private fun prefetchNext(type: String, id: StremioId, info: TitleInfo, answered: List<ProviderTask>) {
        val imdb = id.imdb ?: return
        val season = id.season ?: return
        val episode = id.episode ?: return
        if (tasks.values.count { it.finishedAt == 0L } >= MAX_RUNNING / 2) return
        val delivered = answered.filter { it.hasLinks }
        if (delivered.isEmpty()) return
        val next = id.copy(episode = episode + 1)
        val titleKey = "$type/$imdb:$season:${episode + 1}"
        scope.launch {
            for (task in delivered) taskFor(titleKey, task.info, task.usefulness, type, next, info)
        }
    }

    /** Keeps at most [MAX_TASKS] finished tasks, dropping the ones that finished longest ago. */
    private fun evictOldFinished() {
        val excess = tasks.size - MAX_TASKS
        if (excess <= 0) return
        tasks.entries.filter { it.value.finishedAt != 0L }.sortedBy { it.value.finishedAt }.take(excess)
            .forEach { tasks.remove(it.key, it.value) }
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
        usefulness: Usefulness,
        type: String,
        parsedId: StremioId,
        info: TitleInfo,
        startAfter: List<ProviderTask> = emptyList(),
    ): ProviderTask {
        val key = "$titleKey|${provider.providerId}"
        val now = clock()
        val fresh = ProviderTask(ProviderRef(provider.name, provider.providerId, provider.language), provider, usefulness, now, startAfter)
        val chosen = tasks.compute(key) { _, current -> if (current != null && isFresh(current, now)) current else fresh }!!
        if (chosen === fresh) {
            // Undispatched: the task queues for the search gate right here, so the gate's queue
            // is in the order the providers were started.
            fresh.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { runProvider(fresh, provider, type, parsedId, info) }
        }
        return chosen
    }

    /** Holds a search place for at most [gateHoldMs]. */
    private suspend fun <T> gated(block: suspend () -> T): T = coroutineScope {
        searchGate.acquire()
        val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) searchGate.release()
        }
        val timer = launch {
            delay(gateHoldMs)
            release()
        }
        try {
            block()
        } finally {
            timer.cancel()
            release()
        }
    }

    private suspend fun runProvider(
        task: ProviderTask,
        provider: ProviderInfoDto,
        type: String,
        parsedId: StremioId,
        info: TitleInfo,
    ) {
        if (task.startAfter.isNotEmpty()) {
            // Not worth a search place until the useful providers have had theirs (but do not wait for ever).
            withTimeoutOrNull(DORMANT_WAIT_MS) {
                while (task.startAfter.any { it.phase == "search" && it.finishedAt == 0L }) delay(POLL_MS)
            }
        }
        var recorded = false
        fun record(outcome: SearchOutcome) {
            if (!recorded) {
                recorded = true
                stats.record(provider.name, outcome)
            }
        }
        try {
            withTimeout(PROVIDER_TIMEOUT_MS) {
                val data = gated {
                    resolveData(client, provider.providerId, type, parsedId, info, timeoutMs = 20_000, cache = matches, onOutcome = ::record)
                } ?: return@withTimeout
                task.matched = true
                task.phase = "links"
                task.lastActivityAt = clock()
                val response = semaphore.withPermit { client.links(provider.providerId, data, budgetMs = 25_000) }
                response.links.map { it.source }.distinct().forEach(stats::noteHoster)
                // Publish the links at once, so a slow probe never costs a stream at the deadline.
                task.phase = "probe"
                publish(task, response.links.map { Item(it) }, response)
                publish(task, probeAll(response.links), response)
            }
        } catch (e: TimeoutCancellationException) {
            record(SearchOutcome.FAIL)
            println("hless: ${provider.name} timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(SearchOutcome.FAIL)
            val code = (e as? EngineException)?.code
            println("hless: ${provider.name} failed: ${e.javaClass.simpleName}${code?.let { " ($it)" }.orEmpty()}")
        } finally {
            task.phase = "done"
            task.finishedAt = clock()
            task.lastActivityAt = task.finishedAt
        }
    }

    private fun publish(task: ProviderTask, items: List<Item>, response: LinksResponseDto) {
        task.result = ProviderResult(task.provider, items, response.subtitles)
        task.lastActivityAt = clock()
    }

    /** Probes every link at once; a probe that takes longer than [PROBE_BUDGET_MS] (queueing included) is given up on. */
    private suspend fun probeAll(links: List<StreamLinkDto>): List<Item> = coroutineScope {
        links.map { link -> async { Item(link, withTimeoutOrNull(PROBE_BUDGET_MS) { prober.probe(link) }) } }.awaitAll()
    }

    /**
     * Waits for the providers that matter. Answers when
     *  1. every provider is done: complete; or
     *  2. every [relevant] provider is done (the dormant ones keep going for the next request); or
     *  3. the deadline passed; or
     *  4. the minimum wait passed, at least one provider has links, no provider that
     *     usually delivers is still working, none that already found the title is
     *     still fetching its links, and nothing has happened for the quiet period.
     * Returns whether every provider is done.
     */
    private suspend fun settle(wanted: List<ProviderTask>, relevant: List<ProviderTask>, started: Long): Boolean {
        while (true) {
            if (wanted.all { it.finishedAt != 0L }) return true
            if (relevant.all { it.finishedAt != 0L }) return false
            val now = clock()
            val elapsed = now - started
            if (elapsed >= deadlineMs) {
                // An empty answer helps nobody: keep waiting a while for the first link.
                if (wanted.any { it.hasLinks } || elapsed >= deadlineMs + emptyWaitMs) return false
            }
            val likelyWorking = relevant.any { it.usefulness == Usefulness.LIKELY && it.finishedAt == 0L }
            val working = relevant.any { it.matched && it.finishedAt == 0L }
            if (!likelyWorking && !working && elapsed >= minWaitMs && relevant.any { it.hasLinks } &&
                now - relevant.maxOf { it.lastActivityAt } >= quietMs
            ) return false
            delay(POLL_MS)
        }
    }

    /** Provider names and phases of the tasks that did not finish, for the log; at most eight, matched ones first. */
    private fun stillWorking(wanted: List<ProviderTask>): String {
        val running = wanted.filter { it.finishedAt == 0L }.sortedByDescending { it.matched }
        val shown = running.take(8).joinToString(",") { "${it.provider.name}:${it.phase}${it.usefulness.name.first()}" }
        return if (running.size > 8) "$shown,+${running.size - 8}" else shown
    }

    private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

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
        private const val MAX_RUNNING = 150
        private const val MAX_TASKS = 1_000
        private const val POLL_MS = 100L
        private const val SEARCH_GATE = 6
        private const val DORMANT_WAIT_MS = 25_000L
    }
}
