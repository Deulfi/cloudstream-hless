package com.lagradost.hless

import java.util.concurrent.ConcurrentHashMap

/**
 * Prints a line at most once per interval for the same key, so a host that
 * fails on every request shows up in the log without flooding it. Callers
 * must put no URL, header, key or exception message in [message].
 */
object RateLimitedLog {
    private val last = ConcurrentHashMap<String, Long>()

    fun log(key: String, message: String, intervalMs: Long = 600_000L, now: Long = System.currentTimeMillis()) {
        val previous = last[key]
        if (previous != null && now - previous < intervalMs) return
        if (last.size > 2_000) last.clear()
        last[key] = now
        println(message)
    }
}
