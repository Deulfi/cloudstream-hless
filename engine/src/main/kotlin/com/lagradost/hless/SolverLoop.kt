package com.lagradost.hless

import com.lagradost.macos.models.SolvePendingDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Remembers hosts the solver could not solve, so a doomed host does not keep
 * the solver's worker busy (a single-worker solver spends 12-15 s on every
 * failed attempt) while solvable hosts wait behind it. Pure and clock-driven
 * so it can be tested.
 */
class HostCooldown(private val cooldownMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val until = ConcurrentHashMap<String, Long>()

    fun failed(host: String) {
        until[host] = clock() + cooldownMs
    }

    fun solved(host: String) {
        until.remove(host)
    }

    fun cooling(host: String): Boolean {
        val end = until[host] ?: return false
        if (clock() >= end) {
            until.remove(host, end)
            return false
        }
        return true
    }
}

private val COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

/**
 * The `Cookie` header for the engine, from the cookies the solver returns. The solver's browser
 * returns every cookie it holds (third-party ones too), and the engine refuses a header with a
 * name or value it cannot use or longer than 4096 characters, so only the cookies that belong to
 * [host] and are well-formed go in, in the order given, until the header is as long as it may be.
 * Null when nothing is left.
 */
internal fun solutionCookieHeader(host: String, cookies: kotlinx.serialization.json.JsonArray, maxLength: Int = 3_800): String? {
    val parts = ArrayList<String>()
    var length = 0
    for (cookie in cookies) {
        val c = cookie as? kotlinx.serialization.json.JsonObject ?: continue
        val name = c["name"]?.jsonPrimitive?.contentOrNull ?: continue
        val value = c["value"]?.jsonPrimitive?.contentOrNull ?: continue
        val domain = c["domain"]?.jsonPrimitive?.contentOrNull?.trimStart('.')?.lowercase()
        val h = host.lowercase()
        if (!domain.isNullOrEmpty() && h != domain && !h.endsWith(".$domain")) continue
        if (!COOKIE_NAME.matches(name) || value.any { it.code !in 32..126 || it == ';' }) continue
        val part = "$name=$value"
        if (length + part.length + 2 > maxLength) continue
        parts += part
        length += part.length + 2
    }
    return parts.joinToString("; ").ifEmpty { null }
}

/**
 * Feeds parked Cloudflare challenge tasks to a FlareSolverr-compatible
 * service (plan 3a). Each task is handled once. The solver, this container
 * and MediaFlow must leave through the same public IP or the clearance
 * cookie is useless.
 *
 * - Concurrency follows the solver: [maxConcurrent] if positive, else the
 *   `workers` its `/health` reports, else 2.
 * - A host that failed is left alone for [cooldownMs] (its tasks expire in the
 *   engine by themselves).
 * - The HTTP call is bounded by the solver's own `maxTimeout` plus a margin.
 * - Only the host of a task is logged, never its URL.
 */
class SolverLoop(
    private val client: EngineClient,
    private val solverUrl: String,
    private val timeoutMs: Long = Config.solverTimeoutMs,
    private val maxConcurrent: Int = Config.solverConcurrency,
    private val cooldownMs: Long = Config.solverCooldownMs,
) {
    private val handled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val cooldown = HostCooldown(cooldownMs)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutMs + 20_000, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs + 25_000, TimeUnit.MILLISECONDS)
        .build()

    fun start(scope: CoroutineScope) {
        scope.launch {
            val permits = Semaphore(concurrency())
            loop(scope, permits)
        }
    }

    private suspend fun concurrency(): Int {
        if (maxConcurrent > 0) return maxConcurrent
        val workers = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("$solverUrl/health").build()).execute().use { response ->
                    Json.parseToJsonElement(response.body?.string().orEmpty())
                        .jsonObject["workers"]?.jsonPrimitive?.intOrNull
                }
            }.getOrNull()
        }
        return (workers ?: 2).coerceIn(1, 4)
    }

    private suspend fun loop(scope: CoroutineScope, permits: Semaphore) {
        while (true) {
            val tasks: List<SolvePendingDto> = try {
                client.pendingSolves(25_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("hless: solve.poll_failed: ${e.javaClass.simpleName}")
                delay(5_000)
                continue
            }
            // Forget tasks the engine no longer lists; each id is handled once.
            handled.retainAll(tasks.map { it.taskId }.toSet())
            var started = 0
            for (task in tasks) {
                if (!handled.add(task.taskId)) continue
                val host = task.targetUrl.toHttpUrlOrNull()?.host ?: "?"
                // A challenge is parked at this site: its providers wait for it in the engine (up to a
                // minute and a half each, holding one of its few search places). Do not start more
                // of them while it is dealt with. See BlockedHosts.
                BlockedHosts.blockAtLeast(host, SOLVING_HOLD_MS)
                if (cooldown.cooling(host)) {
                    println("hless: solve.skipped host=$host (failed recently)")
                    scope.launch { giveUp(task, host) }
                    continue
                }
                started++
                scope.launch { permits.withPermit { solve(task, host) } }
            }
            // The engine answers at once while any task is pending, also one we are
            // already solving or skipped; do not spin on it.
            if (started == 0) delay(IDLE_POLL_MS)
        }
    }

    private suspend fun solve(task: SolvePendingDto, host: String) {
        println("hless: solve.started host=$host")
        val body = buildJsonObject {
            put("cmd", "request.get")
            put("url", task.targetUrl)
            put("maxTimeout", timeoutMs)
            if (task.userAgent != null) put("userAgent", task.userAgent)
        }
        val solved = withContext(Dispatchers.IO) { requestSolution(task, host, body.toString()) }
        if (solved == SolveOutcome.SOLVED) {
            cooldown.solved(host)
            BlockedHosts.unblock(host)
        } else if (solved == SolveOutcome.REFUSED) {
            cooldown.failed(host)
            // The providers of this site are left alone for as long: see BlockedHosts.
            BlockedHosts.block(host, cooldownMs)
            giveUp(task, host)
        } else {
            // The solver could not be reached at all (a name that does not resolve, a stopped
            // container). The engine keeps the challenged request waiting for a minute and a half
            // and holds one of its few search places meanwhile, so the providers of that site are
            // left alone for a short while instead of being asked again at once.
            BlockedHosts.block(host, minOf(cooldownMs, 120_000L))
            giveUp(task, host)
        }
    }

    /**
     * Lets the engine stop waiting. It only takes cookies: it retries the request with these, finds
     * the challenge still there, and refuses to park that site again for ten minutes, so the
     * provider fails at once instead of after 90 seconds.
     */
    private suspend fun giveUp(task: SolvePendingDto, host: String) {
        try {
            client.completeSolve(task.taskId, task.targetUrl, GIVE_UP_COOKIE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the task may have expired already; nothing to release then
        }
    }

    private enum class SolveOutcome { SOLVED, REFUSED, UNAVAILABLE }

    private suspend fun requestSolution(task: SolvePendingDto, host: String, body: String): SolveOutcome {
        val response = try {
            http.newCall(
                Request.Builder().url("$solverUrl/v1").post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
            ).execute()
        } catch (e: Exception) {
            println("hless: solve.failed host=$host solver unreachable or timed out: ${e.javaClass.simpleName}")
            return SolveOutcome.UNAVAILABLE
        }
        val text = response.use { it.body?.string().orEmpty() to it.code }
        val root = runCatching { Json.parseToJsonElement(text.first) }.getOrNull()?.jsonObject
        if (root == null) {
            println("hless: solve.failed host=$host bad solver response (HTTP ${text.second})")
            return SolveOutcome.UNAVAILABLE
        }
        if (root["status"]?.jsonPrimitive?.contentOrNull != "ok") {
            println("hless: solve.failed host=$host message=${root["message"]?.jsonPrimitive?.contentOrNull}")
            return SolveOutcome.REFUSED
        }
        val solution = root["solution"]?.jsonObject
        val cookieHeader = solution?.get("cookies")?.jsonArray?.let { solutionCookieHeader(host, it) }
        if (cookieHeader.isNullOrEmpty()) {
            println("hless: solve.failed host=$host no cookies in solution")
            return SolveOutcome.REFUSED
        }
        val sentAgent = task.userAgent
        val solvedAgent = solution?.get("userAgent")?.jsonPrimitive?.contentOrNull
        if (sentAgent != null && solvedAgent != null && solvedAgent != sentAgent) {
            println("hless: solve.warn host=$host the solver used another user agent than the engine asked for")
        }
        return try {
            client.completeSolve(task.taskId, task.targetUrl, cookieHeader)
            println("hless: solve.completed host=$host")
            SolveOutcome.SOLVED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("hless: solve.failed host=$host callback: ${e.javaClass.simpleName}")
            SolveOutcome.UNAVAILABLE
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val IDLE_POLL_MS = 750L
        private const val SOLVING_HOLD_MS = 120_000L
        private const val GIVE_UP_COOKIE = "hless-giveup=1"
    }
}
