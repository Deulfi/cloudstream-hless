package com.lagradost.hless

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.lagradost.macos.CloudDirs
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A small JSON object store on one file: `{key: {"at": <epochMs>, "value": …}}`.
 * Reads scan the whole file; writes are atomic (temp file + rename) and happen
 * at most once every 30 s and on [close].
 */
class JsonFileStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val data = ConcurrentHashMap<String, JsonElement>()
    private val lock = ReentrantLock()
    private var lastSaveAt = 0L

    init {
        if (file.exists()) {
            val parsed = runCatching { json.parseToJsonElement(file.readText()) }.getOrNull()
            val obj = parsed as? JsonObject
            if (obj != null) {
                for ((key, entry) in obj) data[key] = entry
            }
        }
    }

    fun get(key: String): JsonElement? = data[key]

    fun put(key: String, value: JsonElement) {
        data[key] = buildJsonObject {
            put("at", JsonPrimitive(clock()))
            put("value", value)
        }
        saveIfDue()
    }

    fun remove(key: String) {
        if (data.remove(key) != null) saveIfDue()
    }

    fun close() {
        lock.withLock {
            if (data.isNotEmpty() || lastSaveAt == 0L) saveNow()
        }
    }

    private fun saveIfDue() {
        lock.withLock {
            val now = clock()
            if (now - lastSaveAt >= 30_000) saveNow(now)
        }
    }

    private fun saveNow(now: Long = clock()) {
        val parent = file.parentFile
        if (parent != null) parent.mkdirs()
        val payload = buildJsonObject {
            for ((key, entry) in data) put(key, entry)
        }
        val temp = File(parent, file.name + ".tmp")
        temp.writeText(json.encodeToString(JsonObject.serializer(), payload))
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            // ponytail: atomic move unsupported on this filesystem; plain rename
            // is still crash-safe enough for caches.
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) {
                    temp.delete()
                    throw IllegalStateException("could not save ${file.name}")
                }
            }
        }
        lastSaveAt = now
    }
}

/**
 * Cache of resolved /links data per (imdb, providerId).
 * Hits last 30 days, misses 1 hour.
 */
class MatchCache(
    private val store: JsonFileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class Entry {
        data class Hit(val url: String, val at: Long) : Entry()
        data class Miss(val at: Long) : Entry()
        data object Absent : Entry()
    }

    fun lookup(imdb: String, providerId: String): Entry {
        val stored = store.get(key(imdb, providerId)) as? JsonObject ?: return Entry.Absent
        val at = stored.get("at")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val age = clock() - at
        val value = stored.get("value")
        return when {
            value == null || value === JsonNull -> if (age < MISS_TTL) Entry.Miss(at) else Entry.Absent
            age < HIT_TTL -> Entry.Hit(value.jsonPrimitive.content, at)
            else -> Entry.Absent
        }
    }

    fun store(imdb: String, providerId: String, url: String?) {
        store.put(key(imdb, providerId), url?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    fun forget(imdb: String, providerId: String) {
        store.remove(key(imdb, providerId))
    }

    private fun key(imdb: String, providerId: String) = "$imdb|$providerId"

    companion object {
        private val HIT_TTL = TimeUnit.DAYS.toMillis(30)
        private val MISS_TTL = TimeUnit.HOURS.toMillis(1)
    }
}

/** The process-wide stores, rooted at the engine's data directory. */
object HlessStores {
    private val dataDir: File by lazy { File(CloudDirs.home, "hless") }

    val matchStore: JsonFileStore by lazy { JsonFileStore(File(dataDir, "matches.json")) }
    val titleStore: JsonFileStore by lazy { JsonFileStore(File(dataDir, "titles.json")) }
    val kitsuStore: JsonFileStore by lazy { JsonFileStore(File(dataDir, "kitsu.json")) }

    fun shutdown() {
        runCatching { matchStore.close() }
        runCatching { titleStore.close() }
        runCatching { kitsuStore.close() }
    }
}
