package com.lagradost.hless

import com.lagradost.macos.models.SolvePendingDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Feeds parked Cloudflare challenge tasks to a FlareSolverr-compatible
 * service (plan 3a). At most [maxConcurrent] solves run at once; each task
 * is handed out once. The solver, this container and MediaFlow must leave
 * through the same public IP or the clearance cookie is useless.
 */
class SolverLoop(
    private val client: EngineClient,
    private val solverUrl: String,
    private val timeoutMs: Long = Config.solverTimeoutMs,
    private val maxConcurrent: Int = 2,
) {
    private val inFlight = HashSet<String>()
    private val permits = Semaphore(maxConcurrent)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // The solver may take the full maxTimeout before answering.
        .readTimeout(timeoutMs / 1000 + 30, TimeUnit.SECONDS)
        .build()

    fun start(scope: CoroutineScope) {
        scope.launch { loop(scope) }
    }

    private suspend fun loop(scope: CoroutineScope) {
        while (true) {
            val tasks: List<SolvePendingDto> = try {
                client.pendingSolves(25_000)
            } catch (e: Exception) {
                println("hless: solve.poll_failed: ${e.javaClass.simpleName}: ${e.message}")
                delay(5_000)
                continue
            }
            for (task in tasks) {
                if (!inFlight.add(task.taskId)) continue
                scope.launch {
                    permits.acquire()
                    try {
                        solve(task)
                    } finally {
                        permits.release()
                        inFlight.remove(task.taskId)
                    }
                }
            }
        }
    }

    private suspend fun solve(task: SolvePendingDto) {
        println("hless: solve.started task=${task.taskId} url=${task.targetUrl}")
        val body = buildJsonObject {
            put("cmd", "request.get")
            put("url", task.targetUrl)
            put("maxTimeout", timeoutMs)
            if (task.userAgent != null) put("userAgent", task.userAgent)
        }
        withContext(Dispatchers.IO) {
            val response = runCatching {
                http.newCall(
                    Request.Builder()
                        .url(solverUrl + "/v1")
                        .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build(),
                ).execute()
            }.getOrElse { e ->
                println("hless: solve.failed task=${task.taskId} solver unreachable: ${e.javaClass.simpleName}")
                return@withContext
            }
            response.use { resp ->
                val text = resp.body?.string().orEmpty()
                val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()?.jsonObject
                if (root == null) {
                    println("hless: solve.failed task=${task.taskId} bad solver response (HTTP ${resp.code})")
                    return@use
                }
                if (root["status"]?.jsonPrimitive?.contentOrNull != "ok") {
                    println(
                        "hless: solve.failed task=${task.taskId} " +
                            "message=${root["message"]?.jsonPrimitive?.contentOrNull}",
                    )
                    return@use
                }
                val solution = root["solution"]?.jsonObject
                val cookieHeader = solution?.get("cookies")?.jsonArray
                    ?.mapNotNull { cookie ->
                        val c = cookie.jsonObject
                        val name = c["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val value = c["value"]?.jsonPrimitive?.contentOrNull
                        "$name=$value"
                    }
                    ?.joinToString("; ")
                if (cookieHeader.isNullOrEmpty()) {
                    println("hless: solve.failed task=${task.taskId} no cookies in solution")
                    return@use
                }
                val sentAgent = task.userAgent
                val solvedAgent = solution?.get("userAgent")?.jsonPrimitive?.contentOrNull
                if (sentAgent != null && solvedAgent != null && solvedAgent != sentAgent) {
                    println(
                        "hless: solve.warn task=${task.taskId} userAgent differs: " +
                            "sent=$sentAgent solved=$solvedAgent",
                    )
                }
                try {
                    client.completeSolve(task.taskId, task.targetUrl, cookieHeader)
                    println("hless: solve.completed task=${task.taskId}")
                } catch (e: Exception) {
                    println("hless: solve.failed task=${task.taskId} callback: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()!!
    }
}
