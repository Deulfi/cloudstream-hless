package com.lagradost.hless

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals

class HostCooldownTest {
    @Test
    fun `a failed host is left alone for the cooldown, other hosts are not`() {
        val now = AtomicLong(0)
        val cooldown = HostCooldown(10 * 60_000L) { now.get() }
        assertEquals(false, cooldown.cooling("a.example"))
        cooldown.failed("a.example")
        assertEquals(true, cooldown.cooling("a.example"))
        assertEquals(false, cooldown.cooling("b.example"))
        now.set(9 * 60_000L)
        assertEquals(true, cooldown.cooling("a.example"))
        now.set(10 * 60_000L)
        assertEquals(false, cooldown.cooling("a.example"))
    }

    @Test
    fun `a solved host is forgotten at once`() {
        val cooldown = HostCooldown(60_000L) { 0L }
        cooldown.failed("a.example")
        cooldown.solved("a.example")
        assertEquals(false, cooldown.cooling("a.example"))
    }

    @Test
    fun `a new failure starts the cooldown again`() {
        val now = AtomicLong(0)
        val cooldown = HostCooldown(1_000L) { now.get() }
        cooldown.failed("a.example")
        now.set(1_500L)
        assertEquals(false, cooldown.cooling("a.example"))
        cooldown.failed("a.example")
        assertEquals(true, cooldown.cooling("a.example"))
    }
}
