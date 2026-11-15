package com.lagradost.hless

/** A Stremio id: `tt123` for movies, `tt123:1:2` for episodes. */
data class StremioId(
    val imdb: String,
    val season: Int?,
    val episode: Int?,
)

/**
 * Splits a Stremio id. Returns null when the type and the id shape disagree:
 * a movie id with no colon, a series id with two colon-separated integers.
 * The imdb part must be `tt` plus digits.
 */
fun parseStremioId(type: String, id: String): StremioId? = when (type) {
    "movie" -> {
        if (id.contains(":") || !IMDB_ID.matches(id)) null else StremioId(id, null, null)
    }
    "series" -> {
        val parts = id.split(":")
        if (parts.size != 3) return null
        if (!IMDB_ID.matches(parts[0])) return null
        val season = parts[1].toIntOrNull()
        val episode = parts[2].toIntOrNull()
        if (season == null || episode == null) return null
        StremioId(parts[0], season, episode)
    }
    else -> null
}

private val IMDB_ID = Regex("""tt\d+""")
