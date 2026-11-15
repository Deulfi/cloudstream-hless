package com.lagradost.hless

import com.lagradost.macos.models.DetailsDto
import com.lagradost.macos.models.EpisodeDto
import com.lagradost.macos.models.SearchResultDto
import me.xdrop.fuzzywuzzy.FuzzySearch

/** Lowercase, drop a trailing ` (2019)` / ` [2019]`, `&` -> `and`, keep letters and digits. */
fun normalize(title: String): String {
    val withoutYear = TRAILING_YEAR.replace(title.lowercase(), "")
    return withoutYear.replace("&", "and").filter { it.isLetterOrDigit() }
}

/** The engine meta types that may serve a Stremio type. */
fun wantedTypes(type: String): Set<String> = when (type) {
    "movie" -> setOf("Movie", "AnimeMovie", "Documentary", "Cartoon", "Others")
    "series" -> setOf("TvSeries", "Anime", "OVA", "Cartoon", "AsianDrama", "Documentary", "Others")
    else -> emptySet()
}

/**
 * Scores every result against every name of [info] and returns the best one
 * whose total is at least 60, else null.
 *
 * Name: exact after normalization 70, fuzzy ratio >= 92 gives 55, else the
 * result is discarded. Type in [wantedTypes] +15, else discarded. Year:
 * equal +15, off by one +5, off by two or more -40, missing year 0.
 */
fun pickMatch(info: TitleInfo, type: String, results: List<SearchResultDto>): SearchResultDto? {
    val wanted = wantedTypes(type)
    var best: SearchResultDto? = null
    var bestScore = 0
    for (result in results) {
        if (result.type !in wanted) continue
        val nameScore = info.names
            .mapNotNull { name ->
                val normalized = normalize(result.name)
                val target = normalize(name)
                when {
                    normalized == target -> 70
                    FuzzySearch.ratio(normalized, target) >= 92 -> 55
                    else -> null
                }
            }
            .maxOrNull()
            ?: continue
        var score = nameScore + 15
        val resultYear = result.year
        val infoYear = info.year
        if (resultYear != null && infoYear != null) {
            val diff = kotlin.math.abs(resultYear - infoYear)
            score += when {
                diff == 0 -> 15
                diff == 1 -> 5
                else -> -40
            }
        }
        if (score > bestScore) {
            bestScore = score
            best = result
        }
    }
    return if (bestScore >= 60) best else null
}

/**
 * Finds the episode to hand to /links:
 * 1. an entry with this season and episode number;
 * 2. if no entry in the list has a season at all and season == 1: the entry
 *    with this episode number;
 * 3. else null (season not found);
 * 4. prefer audio == "sub", then audio == null, then the first.
 */
fun pickEpisode(details: DetailsDto, season: Int, episode: Int): EpisodeDto? {
    val candidates = details.episodes.filter { it.season == season && it.episode == episode }
    val pool = if (candidates.isNotEmpty()) {
        candidates
    } else if (season == 1 && details.episodes.none { it.season != null }) {
        details.episodes.filter { it.episode == episode }
    } else {
        return null
    }
    return pool.firstOrNull { it.audio == "sub" }
        ?: pool.firstOrNull { it.audio == null }
        ?: pool.first()
}

private val TRAILING_YEAR = Regex("""\s*[\(\[]\d{4}[\)\]]\s*$""")
