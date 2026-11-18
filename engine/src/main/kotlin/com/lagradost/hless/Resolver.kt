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
    var matchName: String? = null
    val url: String? = when (val hit = cache.lookup(key, providerId)) {
        is MatchCache.Entry.Absent -> {
            val searchStarted = System.nanoTime()
            var match: SearchResultDto? = null
            for (name in info.names.take(2)) {
                val results = client.search(providerId, name, timeoutMs)
                pickMatch(info, type, results, kitsu, id.episode)?.let {
                    match = it
                    break
                }
            }
            if (match != null) {
                cache.store(key, providerId, match.url)
                matchName = match.name
                onOutcome(SearchOutcome.HIT)
                match.url
            } else {
                // The engine answers an empty list both for "the site does not have it" and
                // for a search that failed (blocked, down, timed out). A failure is slow, so
                // a slow empty answer is not remembered as "not there".
                if ((System.nanoTime() - searchStarted) / 1_000_000 < SLOW_SEARCH_MS) {
                    cache.store(key, providerId, null)
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
            cache.forget(key, providerId)
            return null
        }
        throw e
    }
    return when {
        kitsu && id.episode == null -> details.movieData
            ?: details.episodes.singleOrNull()?.data
        kitsu -> pickAnimeEpisode(details, info.names.firstOrNull() ?: "", matchName, id.episode!!)?.data
        type == "movie" -> details.movieData
        type == "series" -> pickEpisode(details, id.season!!, id.episode!!)?.data
        else -> null
    }
}
