package com.lagradost.hless

import com.lagradost.macos.models.DetailsDto
import com.lagradost.macos.models.SearchResultDto

private val defaultCache = MatchCache(HlessStores.matchStore)

/** A search that took this long and found nothing probably failed; do not cache it as a miss. */
private const val SLOW_SEARCH_MS = 6_000L

/**
 * Resolves the /links data string for one (provider, title), 2d step 3:
 * search (at most the first two names) -> pickMatch -> cache -> details ->
 * movieData or episode.data.
 *
 * For a Kitsu id (3d) the wanted types come from [wantedKitsuTypes], the
 * cache key is `kitsu:<id>`, and the details pick is
 * [pickAnimeEpisode] (or the only episode, when the provider models films
 * as one episode).
 *
 * Network failures are not stored as misses; a details 404 / provider_no_data
 * forgets the cached url.
 */
suspend fun resolveData(
    client: EngineClient,
    providerId: String,
    type: String,
    id: StremioId,
    info: TitleInfo,
    timeoutMs: Long,
    cache: MatchCache = defaultCache,
    /** Told once how the search came out, for the provider statistics. */
    onOutcome: (SearchOutcome) -> Unit = {},
): String? {
    val key = id.imdb ?: id.kitsu?.let { "kitsu:$it" } ?: return null
    val kitsu = id.kitsu != null
    // Providers list a series as one entry, one per season ("Fallout - Staffel 1") or one per
    // episode ("Fallout S01E04"); those two are remembered per season and per episode.
    val season = id.season
    val episode = id.episode
    val perEntry = !kitsu && type == "series" && season != null && episode != null
    val seasonKey = "$key:S$season"
    val episodeKey = "$key:S${season}E$episode"
    var kind = EntryKind.SERIES
    var matchName: String? = null
    var cached: MatchCache.Entry = cache.lookup(key, providerId)
    if (perEntry) {
        // An old whole-series miss says nothing about per-episode entries.
        if (cached is MatchCache.Entry.Miss) cached = MatchCache.Entry.Absent
        if (cached is MatchCache.Entry.Absent) {
            val s1 = cache.lookup(seasonKey, providerId)
            if (s1 is MatchCache.Entry.Hit) { cached = s1; kind = EntryKind.SEASON }
        }
        if (cached is MatchCache.Entry.Absent) {
            val e1 = cache.lookup(episodeKey, providerId)
            if (e1 is MatchCache.Entry.Hit) { cached = e1; kind = EntryKind.EPISODE } else cached = e1
        }
    }
    val url: String? = when (val hit = cached) {
        is MatchCache.Entry.Absent -> {
            val searchStarted = System.nanoTime()
            var match: SearchResultDto? = null
            var sawEpisodes = false
            for (name in info.names.take(2)) {
                val results = client.search(providerId, name, timeoutMs)
                val plain = pickMatch(info, type, results, kitsu, id.episode)
                if (plain != null) {
                    match = plain
                    break
                }
                if (perEntry) {
                    pickSeriesEntry(info, results, season!!, episode!!)?.let {
                        match = it.result
                        kind = it.kind
                    }
                    if (match != null) break
                    sawEpisodes = sawEpisodes || listsEpisodes(info, results)
                }
            }
            if (match == null && perEntry && sawEpisodes) {
                // The site lists episodes one by one and the list was cut short: ask for this one.
                val results = client.search(
                    providerId, "${info.names.first()} S%02dE%02d".format(season, episode), timeoutMs,
                )
                pickSeriesEntry(info, results, season!!, episode!!)?.let {
                    match = it.result
                    kind = it.kind
                }
            }
            val storeKey = when {
                !perEntry -> key
                match == null -> episodeKey
                kind == EntryKind.SERIES -> key
                kind == EntryKind.SEASON -> seasonKey
                else -> episodeKey
            }
            val found = match
            if (found != null) {
                cache.store(storeKey, providerId, found.url)
                matchName = found.name
                onOutcome(SearchOutcome.HIT)
                found.url
            } else {
                // The engine answers an empty list both for "the site does not have it" and
                // for a search that failed (blocked, down, timed out). A failure is slow, so
                // a slow empty answer is not remembered as "not there".
                if ((System.nanoTime() - searchStarted) / 1_000_000 < SLOW_SEARCH_MS) {
                    cache.store(storeKey, providerId, null)
                    onOutcome(SearchOutcome.MISS)
                } else {
                    onOutcome(SearchOutcome.FAIL)
                }
                null
            }
        }
        is MatchCache.Entry.Miss -> null
        is MatchCache.Entry.Hit -> hit.url.also { onOutcome(SearchOutcome.HIT) }
    }
    if (url == null) return null
    val details = try {
        client.details(providerId, url, timeoutMs)
    } catch (e: EngineException) {
        if (e.status == 404 || e.code == "provider_no_data") {
            cache.forget(
                when (kind) {
                    EntryKind.SERIES -> key
                    EntryKind.SEASON -> seasonKey
                    EntryKind.EPISODE -> episodeKey
                },
                providerId,
            )
            return null
        }
        throw e
    }
    return when {
        kitsu && id.episode == null -> details.movieData
            ?: details.episodes.singleOrNull()?.data
        kitsu -> pickAnimeEpisode(details, info.names.firstOrNull() ?: "", matchName, id.episode!!)?.data
        type == "movie" -> details.movieData
        type == "series" && kind == EntryKind.EPISODE ->
            details.movieData ?: pickEpisode(details, id.season!!, id.episode!!)?.data
        type == "series" && kind == EntryKind.SEASON ->
            pickSeasonEntryEpisode(details, id.season!!, id.episode!!)?.data
        type == "series" -> pickEpisode(details, id.season!!, id.episode!!)?.data
        else -> null
    }
}
