package com.lagradost.hless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlessIdTest {

    @Test
    fun `title round trip with colon and slash in the url`() {
        val id = HlessId("inst:prov", "https://example.com/a:b/c?d=e&f=g#h")
        val encoded = id.encode()
        assertEquals(id, HlessId.decode(encoded))
        // exactly the one separator between the two segments
        assertEquals(1, encoded.removePrefix("hless:").count { it == ':' })
    }

    @Test
    fun `episode round trip`() {
        val id = HlessId("prov", "https://example.com/x", season = 2, episode = 15)
        assertEquals(id, HlessId.decode(id.encode()))
    }

    @Test
    fun `encoded ids use only url-safe characters`() {
        val encoded = HlessId("a b:c/d", "https://x.y/z?w=#frag").encode()
        encoded.removePrefix("hless:").forEach { ch ->
            assertTrue(
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == ':',
                "unexpected $ch in $encoded",
            )
        }
    }

    @Test
    fun `decode rejects malformed ids`() {
        assertNull(HlessId.decode("https://example.com"))
        assertNull(HlessId.decode("hless:"))
        assertNull(HlessId.decode("hless:+"))
        assertNull(HlessId.decode("hless:aGVsbG8:"))
        assertNull(HlessId.decode("hless:aGVsbG8:aGVsbG8:1"))
        assertNull(HlessId.decode("hless:aGVsbG8:aGVsbG8:1:abc"))
        assertNull(HlessId.decode("hless:aGVsbG8:aGVsbG8:abc:2"))
    }
}
