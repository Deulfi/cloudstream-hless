package com.lagradost.hless

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TMDB lookup: preferred-language names first, then original, then English,
 * deduplicated by normalization, at most 4; year from the first date field.
 */
object Tmdb {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var rejected = false
    private val rejectedLogged = AtomicBoolean(false)

    /** True once the key was rejected; behave as if no key is set until restart. */
    fun isRejected(): Boolean = rejected

    /** Pure parse of the two responses, testable against fixture JSON. */
    fun buildNames(type: String, find: JsonElement, translations: JsonElement, langs: List<String>): TitleInfo? {
        val field = if (type == "movie") "title" else "name"
        val originalField = if (type == "movie") "original_title" else "original_name"
        val dateField = if (type == "movie") "release_date" else "first_air_date"
        val findObj = find as? JsonObject ?: return null
        val results = findObj.get(if (type == "movie") "movie_results" else "tv_results") as? JsonArray
        val first = results?.getOrNull(0) as? JsonObject ?: return null
        val english = first.get(field)?.jsonPrimitive?.content
        val original = first.get(originalField)?.jsonPrimitive?.content
        val date = first.get(dateField)?.jsonPrimitive?.content

        val translationList = (translations as? JsonObject)?.get("translations") as? JsonArray
        fun titleFor(lang: String): String? =
            translationList
                ?.firstOrNull { (it as? JsonObject)?.get("iso_639_1")?.jsonPrimitive?.content?.equals(lang, ignoreCase = true) == true }
                ?.let { it as? JsonObject }
                ?.let { it.get("data") as? JsonObject }
                ?.get(field)
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it.isNotBlank() }

        val names = ArrayList<String>()
        for (lang in langs) titleFor(lang)?.let { names.add(it) }
        original?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        english?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        val seen = HashSet<String>()
        val unique = names.filter { seen.add(normalize(it)) }
        if (unique.isEmpty()) return null
        val year = date?.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()
        return TitleInfo(unique.take(4), year)
    }
    /** Pure: the TMDB id of the first find result, or null when TMDB has no entry. */
    fun findId(type: String, find: JsonElement?): Int? {
        val findObj = find as? JsonObject ?: return null
        val results = findObj.get(if (type == "movie") "movie_results" else "tv_results") as? JsonArray
        return (results?.getOrNull(0) as? JsonObject)?.get("id")?.jsonPrimitive?.content?.toIntOrNull()
    }

    suspend fun lookup(type: String, imdb: String, key: String, langs: List<String>): TitleInfo? =
        withContext(Dispatchers.IO) {
            if (rejected) return@withContext null
            val isV4 = key.startsWith("eyJ")
            fun request(path: String, query: (HttpUrl.Builder) -> Unit): Request {
                val urlBuilder = "https://api.themoviedb.org/3$path".toHttpUrl().newBuilder().apply { query(this) }
                if (!isV4) urlBuilder.addQueryParameter("api_key", key)
                val builder = Request.Builder().url(urlBuilder.build())
                return if (isV4) builder.header("Authorization", "Bearer $key").build() else builder.build()
            }

            val findResponse = runCatching {
                client.newCall(request("/find/$imdb") { it.addQueryParameter("external_source", "imdb_id") }).execute()
            }.getOrNull() ?: return@withContext null
            var findJson: JsonElement? = null
            findResponse.use {
                when {
                    it.code == 401 -> {
                        markRejected()
                        return@withContext null
                    }
                    it.isSuccessful -> findJson = runCatching { json.parseToJsonElement(it.body!!.string()) }.getOrNull()
                    else -> return@withContext null
                }
            }
            val tmdbId = findId(type, findJson) ?: return@withContext null

            val path = if (type == "movie") "/movie/$tmdbId" else "/tv/$tmdbId"
            val transResponse = runCatching {
                client.newCall(request(path) { it.addQueryParameter("append_to_response", "translations") }).execute()
            }.getOrNull()
                ?: return@withContext null
            var transJson: JsonElement? = null
            transResponse.use {
                when {
                    it.code == 401 -> {
                        markRejected()
                        return@withContext null
                    }
                    it.isSuccessful -> transJson = runCatching { json.parseToJsonElement(it.body!!.string()) }.getOrNull()
                    else -> return@withContext null
                }
            }
            if (transJson == null || findJson == null) return@withContext null
            buildNames(type, findJson, transJson, langs)
        }

    private fun markRejected() {
        rejected = true
        if (rejectedLogged.compareAndSet(false, true)) {
            println("hless: TMDB key rejected; falling back to Cinemeta until restart")
        }
    }
}
