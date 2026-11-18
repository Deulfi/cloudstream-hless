package com.lagradost.hless

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockedHostsTest {
    @AfterTest
    fun reset() = BlockedHosts.clear()

    @Test
    fun `a blocked host and its subdomains are blocked until the time is up`() {
        BlockedHosts.block("kinoger.com", 1_000, now = 0)
        assertEquals(true, BlockedHosts.isBlocked("kinoger.com", now = 500))
        assertEquals(true, BlockedHosts.isBlocked("www.kinoger.com", now = 500))
        assertEquals(true, BlockedHosts.isBlocked("KINOGER.COM", now = 500))
        assertEquals(false, BlockedHosts.isBlocked("kinoger.com", now = 1_000))
        assertEquals(false, BlockedHosts.isBlocked("kinoger.com", now = 5_000))
    }

    @Test
    fun `blockAtLeast never shortens a block`() {
        BlockedHosts.block("kinoger.com", 600_000, now = 0)
        BlockedHosts.blockAtLeast("kinoger.com", 1_000, now = 0)
        assertEquals(true, BlockedHosts.isBlocked("kinoger.com", now = 300_000))
        BlockedHosts.blockAtLeast("moflix.xyz", 1_000, now = 0)
        assertEquals(true, BlockedHosts.isBlocked("moflix.xyz", now = 500))
        assertEquals(false, BlockedHosts.isBlocked("moflix.xyz", now = 1_500))
    }

    @Test
    fun `other hosts are not blocked`() {
        BlockedHosts.block("kinoger.com", 60_000, now = 0)
        assertEquals(false, BlockedHosts.isBlocked("notkinoger.com", now = 1))
        assertEquals(false, BlockedHosts.isBlocked("example.org", now = 1))
        assertEquals(false, BlockedHosts.isBlocked(null, now = 1))
        assertEquals(false, BlockedHosts.isBlocked("", now = 1))
    }

    @Test
    fun `a site is blocked when the solver gave up on a subdomain of it, and a solved one is free again`() {
        BlockedHosts.block("cdn.moflix.xyz", 60_000, now = 0)
        assertEquals(true, BlockedHosts.isBlocked("moflix.xyz", now = 1))
        BlockedHosts.unblock("cdn.moflix.xyz")
        assertEquals(false, BlockedHosts.isBlocked("moflix.xyz", now = 1))
    }
}
