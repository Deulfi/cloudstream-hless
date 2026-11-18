package com.lagradost.hless

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/** The names of a title, most useful first, its year and, for a movie, its runtime in minutes when TMDB knows it. */
data class TitleInfo(val names: List<String>, val year: Int?, val runtimeMin: Int? = null)

/** Name lookup for one (type, imdb) pair. */
interface TitleLookup {
    suspend fun lookup(type: String, imdb: String): TitleInfo?
}

/**
 * TMDB (2f) with the Cinemeta fallback (2d), backed by a 30-day disk cache
 * keyed by type, imdb and the language list.
 */
class TitleSource(
    private val store: JsonFileStore = HlessStores.titleStore,
    private val tmdbKey: String? = Config.tmdbKey,
    private val langs: List<String> = Config.langs,
    private val tmdb: suspend (String, String, String, List<String>) -> TitleInfo? = Tmdb::lookup,
    private val cinemeta: suspend (String, String) -> TitleInfo? = Cinemeta::lookup,
) : TitleLookup {

    override suspend fun lookup(type: String, imdb: String): TitleInfo? {
        // v2: entries carry the runtime (5b); older ones have none.
        val key = "v2|$type|$imdb|${langs.joinToString(",")}"
        val cached = store.get(key)?.let { entry ->
            val at = (entry as? JsonObject)?.get("at")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - at < TTL) titleInfoFrom(entry) else null
        }
        if (cached != null) return cached
        val askedTmdb = tmdbKey != null && !Tmdb.isRejected()
        val info = if (askedTmdb) tmdb(type, imdb, tmdbKey!!, langs) else null
        if (info != null) {
            store.put(key, titleInfoJson(info))
            return info
        }
        val fallback = cinemeta(type, imdb) ?: return null
        // The English-only fallback is kept only when TMDB was not an option. If TMDB
        // was asked and failed, a bad moment must not leave the title without its
        // translated names for a month.
        if (!askedTmdb) store.put(key, titleInfoJson(fallback))
        return fallback
    }

    companion object {
        private val TTL = TimeUnit.DAYS.toMillis(30)
    }
}

/** The on-disk value of a [TitleInfo] (2f, 3d). */
fun titleInfoJson(info: TitleInfo): JsonObject = buildJsonObject {
    put("names", JsonArray(info.names.map { JsonPrimitive(it) }))
    put("year", info.year?.let { JsonPrimitive(it) } ?: JsonNull)
    put("runtimeMin", info.runtimeMin?.let { JsonPrimitive(it) } ?: JsonNull)
}

fun titleInfoFrom(entry: JsonElement): TitleInfo? {
    val value = (entry as? JsonObject)?.get("value") as? JsonObject ?: return null
    val names = value.get("names")?.jsonArray?.map { it.jsonPrimitive.content } ?: return null
    val year = value.get("year")?.jsonPrimitive?.content?.toIntOrNull()
    val runtime = value.get("runtimeMin")?.jsonPrimitive?.content?.toIntOrNull()
    return TitleInfo(names, year, runtime)
}
