package com.lagradost.hless

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Kitsu lookup (3d): one entry's names and start year from
 * `https://kitsu.io/api/edge/anime/<id>`.
 */
object Kitsu {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** The entry's `data.attributes`, or null. */
    suspend fun detail(id: String): JsonObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://kitsu.io/api/edge/anime/$id")
            .header("Accept", "application/vnd.api+json")
            .build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull()
            ?: return@withContext null
        response.use {
            if (!it.isSuccessful) return@withContext null
            val root = runCatching { json.parseToJsonElement(it.body!!.string()) }.getOrNull()
                ?: return@withContext null
            root.jsonObject?.get("data")?.jsonObject?.get("attributes")?.jsonObject
        }
    }

    /**
     * The names most useful first: `titles.en`, `titles.en_jp`,
     * `canonicalTitle`, without nulls and duplicates (by [normalize]).
     * Returns null when the entry has no usable title.
     */
    fun parse(attributes: JsonObject): TitleInfo? {
        val titles = attributes["titles"]?.jsonObject
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (name in listOfNotNull(
            string(titles?.get("en")),
            string(titles?.get("en_jp")),
            string(attributes["canonicalTitle"]),
        )) {
            val normalized = normalize(name)
            if (normalized.isNotEmpty() && seen.add(normalized)) names += name
        }
        if (names.isEmpty()) return null
        val year = string(attributes["startDate"])?.take(4)?.toIntOrNull()
        return TitleInfo(names, year)
    }

    /** The string value of a JSON string, or null for other elements. */
    private fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Lowercase, keep letters and digits. */
    fun normalize(name: String): String =
        name.lowercase().replace(Regex("""[^a-z0-9]"""), "")
}

/**
 * Kitsu-backed [TitleLookup] for entries matched by Kitsu id. The Kitsu id
 * is the key, so `lookup` is called with `imdb = kitsuId`. Cached on disk
 * like 2f, key `"kitsu|<id>"`.
 */
class KitsuLookup(
    private val store: JsonFileStore = HlessStores.kitsuStore,
    private val detail: suspend (String) -> JsonObject? = Kitsu::detail,
) : TitleLookup {

    override suspend fun lookup(type: String, imdb: String): TitleInfo? {
        val key = "kitsu|$imdb"
        val cached = store.get(key)?.let { entry ->
            val at = (entry as? JsonObject)?.get("at")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - at < TTL) titleInfoFrom(entry) else null
        }
        if (cached != null) return cached
        val info = detail(imdb)?.let { Kitsu.parse(it) } ?: return null
        store.put(key, titleInfoJson(info))
        return info
    }

    companion object {
        private val TTL = TimeUnit.DAYS.toMillis(30)
    }
}
