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

    private fun cookies(vararg c: Triple<String, String, String?>) = kotlinx.serialization.json.JsonArray(
        c.map { (name, value, domain) ->
            kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("value", kotlinx.serialization.json.JsonPrimitive(value))
                if (domain != null) put("domain", kotlinx.serialization.json.JsonPrimitive(domain))
            }
        },
    )

    @Test
    fun `only the cookies of the challenged site go back to the engine`() {
        val header = solutionCookieHeader(
            "moflix-stream.xyz",
            cookies(
                Triple("cf_clearance", "abc", ".moflix-stream.xyz"),
                Triple("__cf_bm", "def", "moflix-stream.xyz"),
                Triple("tracker", "x", ".ads.example"),
                Triple("plain", "y", null),
            ),
        )
        assertEquals("cf_clearance=abc; __cf_bm=def; plain=y", header)
    }

    @Test
    fun `cookies the engine would refuse are left out, and the header stays short enough`() {
        val header = solutionCookieHeader(
            "a.example",
            cookies(
                Triple("ok", "1", ".a.example"),
                Triple("bad name", "1", ".a.example"),
                Triple("semi", "a;b", ".a.example"),
                Triple("umlaut", "\u00fc", ".a.example"),
                Triple("big", "x".repeat(5_000), ".a.example"),
                Triple("also", "2", ".a.example"),
            ),
        )
        assertEquals("ok=1; also=2", header)
        assertEquals(null, solutionCookieHeader("a.example", cookies(Triple("t", "1", ".other.example"))))
    }
}
