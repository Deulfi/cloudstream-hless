package com.lagradost.hless

import com.lagradost.macos.models.StreamLinkDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The MediaFlow proxy (2e): wraps engine links in MediaFlow proxy URLs and
 * keeps the (cold-starting) service awake.
 */
object MediaFlow {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    @Volatile private var lastWakeOkAt = 0L
    private val wakeRunning = AtomicBoolean(false)

    /** True when both the URL and the password are configured. */
    fun configured(): Boolean = Config.mediaflowUrl != null && Config.mediaflowPassword != null

    /** The MediaFlow proxy URL for one engine link. */
    fun wrap(base: String, password: String, link: StreamLinkDto): String {
        val path = when (link.type) {
            "M3U8" -> "/proxy/hls/manifest.m3u8"
            "DASH" -> "/proxy/mpd/manifest.m3u8"
            else -> "/proxy/stream"
        }
        val builder = (base + path).toHttpUrl().newBuilder()
            .addQueryParameter("d", link.url)
            .addQueryParameter("api_password", password)
        for ((name, value) in link.headers) {
            builder.addQueryParameter("h_" + name.lowercase(), value)
        }
        if (link.type == "DASH" && link.drm != null) {
            link.drm.kid?.let { builder.addQueryParameter("key_id", it) }
            builder.addQueryParameter("key", link.drm.key)
        }
        return builder.build().toString()
    }

    /** The configured wrapper for one link, or null when MediaFlow is not configured. */
    fun wrap(link: StreamLinkDto): String? {
        val base = Config.mediaflowUrl ?: return null
        val password = Config.mediaflowPassword ?: return null
        return wrap(base, password, link)
    }

    /**
     * GETs `<mediaflowUrl>/health` until it answers 200 (5 s timeout per try,
     * 1 s apart, at most 30 tries), ignoring all errors. Skipped when a wake
     * succeeded less than 60 s ago or one is already running.
     */
    fun wake(scope: CoroutineScope) {
        val url = Config.mediaflowUrl ?: return
        if (System.currentTimeMillis() - lastWakeOkAt < 60_000) return
        if (!wakeRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                repeat(30) { attempt ->
                    val ok = runCatching {
                        val response = client.newCall(
                            Request.Builder().url(url + "/health").build(),
                        ).execute()
                        response.use { it.code == 200 }
                    }.getOrDefault(false)
                    if (ok) {
                        lastWakeOkAt = System.currentTimeMillis()
                        return@launch
                    }
                    if (attempt < 29) delay(1_000)
                }
            } finally {
                wakeRunning.set(false)
            }
        }
    }
}
