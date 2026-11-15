package com.lagradost.hless

import com.lagradost.macos.models.DetailsDto
import com.lagradost.macos.models.SearchResultDto

private val defaultCache = MatchCache(HlessStores.matchStore)

/**
 * Resolves the /links data string for one (provider, title), 2d step 3:
 * search (at most the first two names) -> pickMatch -> cache -> details ->
 * movieData or episode.data.
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
): String? {
    val url: String? = when (val hit = cache.lookup(id.imdb, providerId)) {
        is MatchCache.Entry.Absent -> {
            var match: SearchResultDto? = null
            for (name in info.names.take(2)) {
                val results = client.search(providerId, name, timeoutMs)
                pickMatch(info, type, results)?.let {
                    match = it
                    break
                }
            }
            if (match != null) {
                cache.store(id.imdb, providerId, match.url)
                match.url
            } else {
                cache.store(id.imdb, providerId, null)
                null
            }
        }
        is MatchCache.Entry.Miss -> null
        is MatchCache.Entry.Hit -> hit.url
    }
    if (url == null) return null
    val details = try {
        client.details(providerId, url, timeoutMs)
    } catch (e: EngineException) {
        if (e.status == 404 || e.code == "provider_no_data") {
            cache.forget(id.imdb, providerId)
            return null
        }
        throw e
    }
    return when (type) {
        "movie" -> details.movieData
        "series" -> pickEpisode(details, id.season!!, id.episode!!)?.data
        else -> null
    }
}
