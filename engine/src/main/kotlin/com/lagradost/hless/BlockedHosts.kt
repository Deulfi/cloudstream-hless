package com.lagradost.hless

import java.util.concurrent.ConcurrentHashMap

/**
 * Hosts the solver could not get past (a Cloudflare challenge that is never solved). A provider
 * whose own site is blocked is not asked while that lasts: its search would wait for the solver
 * for up to a minute and hold one of the engine's few search places the whole time, which stalls
 * the providers that would have answered.
 */
object BlockedHosts {
    private val until = ConcurrentHashMap<String, Long>()

    fun block(host: String, forMs: Long, now: Long = System.currentTimeMillis()) {
        until[host.lowercase()] = now + forMs
    }

    /** Blocks [host] for at least [forMs] from [now]; a longer block already in place stays. */
    fun blockAtLeast(host: String, forMs: Long, now: Long = System.currentTimeMillis()) {
        until.merge(host.lowercase(), now + forMs) { old, new -> maxOf(old, new) }
    }

    fun unblock(host: String) {
        until.remove(host.lowercase())
    }

    /** True while [host], or a site it is a subdomain or the parent of, is blocked. */
    fun isBlocked(host: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        val iterator = until.entries.iterator()
        while (iterator.hasNext()) {
            val (blocked, end) = iterator.next()
            if (now >= end) {
                iterator.remove()
                continue
            }
            if (h == blocked || h.endsWith(".$blocked") || blocked.endsWith(".$h")) return true
        }
        return false
    }

    /** For tests. */
    internal fun clear() = until.clear()
}
