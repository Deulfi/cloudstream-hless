package com.lagradost.hless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    @Test
    fun `allows the limit at once, then one more as time passes`() {
        var now = 0L
        val limiter = RateLimiter(3, clock = { now })
        repeat(3) { assertTrue(limiter.allow("a")) }
        assertFalse(limiter.allow("a"))
        assertTrue(limiter.allow("b"))   // per address
        now += 20_000                   // a third of a minute: one request back
        assertTrue(limiter.allow("a"))
        assertFalse(limiter.allow("a"))
    }

    @Test
    fun `zero means no limit`() {
        val limiter = RateLimiter(0)
        repeat(1_000) { assertTrue(limiter.allow("a")) }
    }

    @Test
    fun `the table does not grow without bound`() {
        var now = 0L
        val limiter = RateLimiter(1, clock = { now }, maxClients = 10)
        repeat(100) { assertTrue(limiter.allow("c$it")) }
        now += 60_000
        assertTrue(limiter.allow("c1"))
    }

    @Test
    fun `behind a proxy the address the proxy added counts, otherwise the connection's`() {
        assertEquals("203.0.113.9", clientAddress("172.18.0.2", "1.2.3.4, 203.0.113.9"))
        assertEquals("203.0.113.9", clientAddress("127.0.0.1", "203.0.113.9"))
        // a public client cannot pick its own address by sending the header
        assertEquals("198.51.100.7", clientAddress("198.51.100.7", "10.0.0.1"))
        assertEquals("172.18.0.2", clientAddress("172.18.0.2", null))
    }
}
