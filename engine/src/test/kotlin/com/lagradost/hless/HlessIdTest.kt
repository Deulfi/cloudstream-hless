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
        // provider, url and signature: two separators
        assertEquals(2, encoded.removePrefix("hless:").count { it == ':' })
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
    fun `an id this server did not sign does not decode`() {
        val good = HlessId("prov", "https://example.com/x").encode()
        val parts = good.split(":")
        // an unsigned id of the old shape
        assertNull(HlessId.decode("hless:" + parts[1] + ":" + parts[2]))
        // a made-up URL under a real signature
        val forged = "hless:" + parts[1] + ":" + HlessId.b64url("http://10.0.0.1/admin") + ":" + parts[3]
        assertNull(HlessId.decode(forged))
        // a signature from another body, an empty one, a tampered season
        assertNull(HlessId.decode("hless:" + parts[1] + ":" + parts[2] + ":AAAAAAAAAAAAAAAAAAAAAA"))
        assertNull(HlessId.decode("hless:" + parts[1] + ":" + parts[2] + ":"))
        val episode = HlessId("prov", "https://example.com/x", 1, 2).encode().split(":")
        assertNull(HlessId.decode("hless:${episode[1]}:${episode[2]}:1:3:${episode[5]}"))
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
