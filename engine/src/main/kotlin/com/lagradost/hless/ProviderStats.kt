package com.lagradost.hless

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/** How worth asking a provider is, from what it did before. */
enum class Usefulness { LIKELY, UNKNOWN, DORMANT }

/** What one search at a provider came to. A failure is evidence too: a provider that never answers is not useful. */
enum class SearchOutcome { HIT, MISS, FAIL }

/**
 * Remembers, per provider, how often it found the title asked for, and which
 * hosters (the `source` of a link) it was seen to offer. Used to search the
 * providers that usually deliver first, to wait only for those, and to fill
 * the configuration page. Kept on disk; old entries expire with the store.
 */
class ProviderStats(
    private val store: JsonFileStore = HlessStores.statsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Counts(val hits: Int, val misses: Int, val fails: Int, val lastHitAt: Long) {
        val tries: Int get() = hits + misses + fails
    }

    private fun value(key: String): JsonObject? = (store.get(key) as? JsonObject)?.get("value") as? JsonObject

    fun counts(provider: String): Counts {
        val v = value("p|$provider") ?: return Counts(0, 0, 0, 0)
        fun n(name: String) = v[name]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        return Counts(n("hits").toInt(), n("misses").toInt(), n("fails").toInt(), n("lastHit"))
    }

    @Synchronized
    fun record(provider: String, outcome: SearchOutcome) {
        val now = clock()
        val c = counts(provider)
        var hits = c.hits
        var misses = c.misses
        var fails = c.fails
        var lastHit = c.lastHitAt
        when (outcome) {
            SearchOutcome.HIT -> { hits++; lastHit = now }
            SearchOutcome.MISS -> misses++
            SearchOutcome.FAIL -> fails++
        }
        // Recent behaviour counts more than old: halve the counts now and then (hits round up, so a provider is not forgotten).
        if (hits + misses + fails > HALVE_AT) {
            hits = (hits + 1) / 2
            misses /= 2
            fails /= 2
        }
        store.put("p|$provider", buildJsonObject {
            put("hits", hits)
            put("misses", misses)
            put("fails", fails)
            put("lastHit", lastHit)
        })
    }

    fun classify(provider: String): Usefulness {
        val c = counts(provider)
        return when {
            c.hits > 0 && clock() - c.lastHitAt < LIKELY_WINDOW_MS -> Usefulness.LIKELY
            c.tries >= DORMANT_AFTER -> Usefulness.DORMANT
            else -> Usefulness.UNKNOWN
        }
    }

    @Synchronized
    fun noteHoster(source: String) {
        val name = source.trim().take(40)
        if (name.isEmpty()) return
        val key = "h|${name.lowercase()}"
        val existing = value(key)
        val seen = (existing?.get("seen")?.jsonPrimitive?.content?.toIntOrNull() ?: 0) + 1
        // The spelling seen first is the one shown.
        val shown = existing?.get("name")?.jsonPrimitive?.content ?: name
        store.put(key, buildJsonObject {
            put("name", JsonPrimitive(shown))
            put("seen", seen)
        })
    }

    /** The hoster names seen, most seen first. */
    fun hosters(limit: Int = 60): List<Pair<String, Int>> =
        store.keys("h|").mapNotNull { key ->
            val v = value(key) ?: return@mapNotNull null
            val name = v["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            name to (v["seen"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
        }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() }).take(limit)

    companion object {
        private val LIKELY_WINDOW_MS = TimeUnit.DAYS.toMillis(30)
        private const val DORMANT_AFTER = 10
        private const val HALVE_AT = 200
    }
}
