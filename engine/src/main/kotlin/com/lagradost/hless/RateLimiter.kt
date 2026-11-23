package com.lagradost.hless

import java.net.InetAddress

/**
 * A token bucket per client address: [perMinute] requests a minute, up to
 * [perMinute] at once. 0 means no limit. Buckets that are full again are
 * forgotten, so the table stays small.
 */
class RateLimiter(
    private val perMinute: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxClients: Int = 10_000,
) {
    private class Bucket(var tokens: Double, var at: Long)

    private val buckets = HashMap<String, Bucket>()

    val enabled: Boolean get() = perMinute > 0

    /** True when [client] may make one more request now (and counts it). */
    @Synchronized
    fun allow(client: String): Boolean {
        if (perMinute <= 0) return true
        val now = clock()
        if (buckets.size >= maxClients) sweep(now)
        val bucket = buckets.getOrPut(client) { Bucket(perMinute.toDouble(), now) }
        bucket.tokens = minOf(perMinute.toDouble(), bucket.tokens + (now - bucket.at) * perMinute / 60_000.0)
        bucket.at = now
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    private fun sweep(now: Long) {
        // A bucket idle for a minute is full again: the same as no bucket.
        buckets.entries.removeIf { now - it.value.at >= 60_000L }
        // Still full (an address flood): start over rather than grow without bound.
        if (buckets.size >= maxClients) buckets.clear()
    }
}

/**
 * The address a request came from. Behind a reverse proxy (the connection comes
 * from a private or loopback address) that is the last `X-Forwarded-For` entry,
 * which the proxy itself added; a client cannot forge it. A connection from a
 * public address is taken as it is, whatever the headers say.
 */
internal fun clientAddress(remote: String, forwardedFor: String?): String {
    val last = forwardedFor?.split(',')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
    if (last == null || !isProxyAddress(remote)) return remote
    return last.take(64)
}

private fun isProxyAddress(remote: String): Boolean {
    if (!isIpLiteral(remote)) return false
    val address = runCatching { InetAddress.getByName(remote) }.getOrNull() ?: return false
    return !isPublicAddress(address)
}
