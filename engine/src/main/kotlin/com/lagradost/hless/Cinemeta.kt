package com.lagradost.hless

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cinemeta lookup: one name, the year from meta.year or meta.releaseInfo.
 * Results are cached for the life of the process.
 */
object Cinemeta {
    private val cache = ConcurrentHashMap<String, TitleInfo>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(type: String, imdb: String): TitleInfo? = withContext(Dispatchers.IO) {
        cache[imdb] ?: run {
            val request = Request.Builder()
                .url("https://v3-cinemeta.strem.io/meta/$type/$imdb.json")
                .build()
            val response = runCatching { client.newCall(request).execute() }.getOrNull()
                ?: return@withContext null
            response.use {
                if (!it.isSuccessful) return@withContext null
                val body = runCatching { json.parseToJsonElement(it.body!!.string()) }.getOrNull()
                    ?: return@withContext null
                val meta = body.jsonObject?.get("meta")?.jsonObject ?: return@withContext null
                val name = meta.get("name")?.jsonPrimitive?.content
                    ?: return@withContext null
                val year = listOfNotNull(
                    meta.get("year")?.jsonPrimitive?.content,
                    meta.get("releaseInfo")?.jsonPrimitive?.content,
                ).firstNotNullOfOrNull { FOUR_DIGITS.find(it)?.value?.toIntOrNull() }
                val info = TitleInfo(listOf(name), year)
                cache[imdb] = info
                info
            }
        }
    }

    private val FOUR_DIGITS = Regex("""\d{4}""")
}
