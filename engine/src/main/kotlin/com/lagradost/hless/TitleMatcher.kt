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

/** Provider tvTypes that make a provider eligible for Kitsu ids (3d). */
val kitsuProviderTypes: Set<String> = setOf("Anime", "AnimeMovie", "OVA")
/** The engine meta types that may serve a Kitsu id (3d). */
fun wantedKitsuTypes(episode: Int?): Set<String> =
    if (episode == null) setOf("AnimeMovie", "Movie", "Anime", "OVA")
    else setOf("Anime", "OVA", "TvSeries", "Cartoon")

/**
 * Scores every result against every name of [info] and returns the best one
 * whose total is at least 60, else null.
 *
 * Name: exact after normalization 70, fuzzy ratio >= 92 gives 55, else the
 * result is discarded. Type in the wanted set +15, else discarded. Year:
 * equal +15, off by one +5, off by two or more -40, missing year 0.
 *
 * For a Kitsu id ([kitsu]) the wanted set comes from
 * [wantedKitsuTypes] with the requested [episode].
 */
fun pickMatch(
    info: TitleInfo,
    type: String,
    results: List<SearchResultDto>,
    kitsu: Boolean = false,
    episode: Int? = null,
): SearchResultDto? {
    val wanted = if (kitsu) wantedKitsuTypes(episode) else wantedTypes(type)
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
                    // Similar spelling counts only when the numbers agree: "spiderman" is
                    // 94% of "spiderman2", and a sequel is a different film.
                    digitsOf(normalized) == digitsOf(target) && FuzzySearch.ratio(normalized, target) >= 92 -> 55
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

/**
 * The trailing season marker of a name, case-insensitive: `Season <n>`,
 * `<n>nd/rd/th Season`, `Part <n>`, a single digit 2-9, or the roman
 * numerals ` II`, ` III`, ` IV`. Null when the name carries none.
 */
fun seasonMarker(name: String): Int? {
    val n = name.lowercase().trim()
    SEASON_N.find(n)?.let { return it.groupValues[1].toInt() }
    ORDINAL_SEASON.find(n)?.let { return it.groupValues[1].toInt() }
    PART_N.find(n)?.let { return it.groupValues[1].toInt() }
    STANDALONE_N.find(n)?.let { return it.groupValues[1].toInt() }
    ROMAN_N.find(n)?.let { return ROMAN[it.groupValues[1]] }
    return null
}

/**
 * Finds the episode of a Kitsu entry (3d). Episodes with `season == 0` are
 * specials and are ignored throughout. Let `seasons` be the distinct seasons
 * of the remaining episodes:
 * 1. at most one season: the episode with `episode == episode`;
 * 2. several seasons and the matched result's name or the entry name ends
 *    in a season marker: that season, the episode with `episode == episode`;
 * 3. several seasons and no marker: the first season, the episode with
 *    `episode == episode`;
 * 4. nothing found by 1-3: absolute numbering - all episodes sorted by
 *    (season, episode), the one at position `episode` (1-based);
 * 5. variants of the same episode: prefer `audio == HLESS_ANIME_AUDIO`,
 *    then null, then the first.
 *
 * [matchName] is the provider's matched result name; when it carries a
 * season marker the provider lists each season as its own title, so the
 * matched result is that season and steps 2-4 are skipped.
 *
 * Known to be wrong sometimes (Kitsu splits into parts while the provider
 * counts through, and the reverse); no special cases.
 */
fun pickAnimeEpisode(
    details: DetailsDto,
    entryName: String,
    matchName: String?,
    episode: Int,
): EpisodeDto? {
    val episodes = details.episodes.filter { it.season != null && it.season != 0 }
    val seasons = episodes.mapNotNull { it.season }.toSet()
    if (seasons.size <= 1) return pickByEpisode(episodes, episode)
    val matchMarker = seasonMarker(matchName ?: "")
    if (matchMarker != null) {
        return pickByEpisode(episodes.filter { it.season == matchMarker }, episode)
            ?: pickByEpisode(episodes, episode)
    }
    val entryMarker = seasonMarker(entryName)
    if (entryMarker != null) {
        pickByEpisode(episodes.filter { it.season == entryMarker }, episode)?.let { return it }
    }
    val first = seasons.minOrNull()
    if (first != null) {
        pickByEpisode(episodes.filter { it.season == first }, episode)?.let { return it }
    }
    val sorted = episodes.sortedWith(compareBy<EpisodeDto> { e -> e.season ?: 0 }.thenBy { e -> e.episode ?: 0 })
    if (episode in 1..sorted.size) {
        val target = sorted[episode - 1]
        val targetEpisode = target.episode ?: return null
        return pickByEpisode(episodes.filter { it.season == target.season && it.episode == targetEpisode }, targetEpisode)
    }
    return null
}

private fun pickByEpisode(pool: List<EpisodeDto>, episode: Int): EpisodeDto? {
    val candidates = pool.filter { it.episode == episode }
    return candidates.firstOrNull { it.audio == Config.animeAudio }
        ?: candidates.firstOrNull { it.audio == null }
        ?: candidates.firstOrNull()
}

private fun digitsOf(text: String): String = text.filter { it.isDigit() }

private val SEASON_N = Regex("""season (\d+)\s*$""")
private val ORDINAL_SEASON = Regex("""(\d+)(?:st|nd|rd|th) season\s*$""")
private val PART_N = Regex("""part (\d+)\s*$""")
private val STANDALONE_N = Regex("""\s([2-9])\s*$""")
private val ROMAN_N = Regex("""\s+(ii|iii|iv)\s*$""")
private val ROMAN = mapOf("ii" to 2, "iii" to 3, "iv" to 4)

private val TRAILING_YEAR = Regex("""\s*[\(\[]\d{4}[\)\]]\s*$""")
