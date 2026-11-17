package com.lagradost.hless

/**
 * A Stremio id: `tt123` for movies, `tt123:1:2` for episodes,
 * `kitsu:123` / `kitsu:123:5` for Kitsu entries (3d).
 */
data class StremioId(
    val imdb: String?,
    val kitsu: String?,
    val season: Int?,
    val episode: Int?,
)

/**
 * Splits a Stremio id. Returns null when the type and the id shape disagree:
 * a movie id with no colon, a series id with two colon-separated integers.
 * The imdb part must be `tt` plus digits; the kitsu part `kitsu:` plus digits.
 * `anime` is a Kitsu type: an id with an episode is a series, without one a
 * movie.
 */
fun parseStremioId(type: String, id: String): StremioId? {
    val kitsu = KITSU_ID.matchEntire(id)
    val kitsuEp = KITSU_EPISODE.matchEntire(id)
    when (type) {
        "movie" -> {
            if (IMDB_ID.matches(id)) return StremioId(id, null, null, null)
            if (kitsu != null) return StremioId(null, kitsu.groupValues[1], null, null)
        }
        "series" -> {
            val parts = id.split(":")
            if (parts.size == 3 && IMDB_ID.matches(parts[0])) {
                val season = parts[1].toIntOrNull()
                val episode = parts[2].toIntOrNull()
                if (season != null && episode != null) return StremioId(parts[0], null, season, episode)
            }
            if (kitsuEp != null) return StremioId(null, kitsuEp.groupValues[1], null, kitsuEp.groupValues[2].toInt())
            if (kitsu != null) return StremioId(null, kitsu.groupValues[1], null, null)
        }
        "anime" -> {
            if (kitsuEp != null) return StremioId(null, kitsuEp.groupValues[1], null, kitsuEp.groupValues[2].toInt())
            if (kitsu != null) return StremioId(null, kitsu.groupValues[1], null, null)
        }
    }
    return null
}

private val IMDB_ID = Regex("""tt\d+""")
private val KITSU_ID = Regex("""kitsu:(\d+)""")
private val KITSU_EPISODE = Regex("""kitsu:(\d+):(\d+)""")
