package com.lagradost.hless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinkFactsTest {
    // Shaped like a real master: three variants and, in addition, trick-play streams with the same resolutions.
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=2642000,RESOLUTION=1920x800
        a.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1453000,RESOLUTION=1280x720
        b.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=932000,RESOLUTION=854x480
        c.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=54000,RESOLUTION=1920x800,URI="ia.m3u8"
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=35000,RESOLUTION=1280x720,URI="ib.m3u8"
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=23000,RESOLUTION=854x480,URI="ic.m3u8"
    """.trimIndent()

    @Test
    fun `a master has three variants, not six`() {
        val facts = assertNotNull(parseHls(master))
        assertEquals(Kind.MASTER, facts.kind)
        // 1920x800 is a cropped 1080p picture.
        assertEquals(listOf(1080, 720, 480), facts.heights)
        assertEquals(2642, facts.topBandwidthKbps)
        assertNull(facts.durationSec)
    }

    @Test
    fun `quality classes follow the width as well as the height`() {
        assertEquals(1080, qualityClass(1920, 1080))
        assertEquals(1080, qualityClass(1920, 800))
        assertEquals(1080, qualityClass(1440, 1080))
        assertEquals(720, qualityClass(1280, 720))
        assertEquals(720, qualityClass(1280, 536))
        assertEquals(480, qualityClass(854, 480))
        assertEquals(480, qualityClass(852, 356))
        assertEquals(360, qualityClass(640, 360))
        assertEquals(240, qualityClass(426, 240))
        assertEquals(2160, qualityClass(3840, 2160))
        assertEquals(2160, qualityClass(3840, 1600))
        assertEquals(1440, qualityClass(2560, 1440))
        assertEquals(144, qualityClass(256, 144))
    }

    @Test
    fun `variants that share a class count once`() {
        val facts = assertNotNull(
            parseHls("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720\na\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x536\nb\n"),
        )
        assertEquals(listOf(720), facts.heights)
    }

    @Test
    fun `average bandwidth wins over peak bandwidth when both are given`() {
        val facts = assertNotNull(
            parseHls("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2500000,RESOLUTION=1280x720\nx.m3u8"),
        )
        assertEquals(2500, facts.topBandwidthKbps)
        assertEquals(listOf(720), facts.heights)
    }

    @Test
    fun `a variant without a resolution still makes a master`() {
        val facts = assertNotNull(parseHls("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nx.m3u8"))
        assertEquals(Kind.MASTER, facts.kind)
        assertEquals(emptyList(), facts.heights)
        assertEquals(800, facts.topBandwidthKbps)
    }

    @Test
    fun `a finished media playlist has its duration`() {
        val facts = assertNotNull(
            parseHls("#EXTM3U\n#EXT-X-TARGETDURATION:11\n#EXTINF:10.0,\ns1.ts\n#EXTINF:10.5,\ns2.ts\n#EXT-X-ENDLIST\n"),
        )
        assertEquals(Kind.MEDIA, facts.kind)
        assertEquals(20.5, facts.durationSec)
        assertEquals(false, facts.live)
    }

    @Test
    fun `a media playlist without an end is live and has no duration`() {
        val facts = assertNotNull(parseHls("#EXTM3U\n#EXTINF:6,\ns1.ts\n#EXTINF:6,\ns2.ts\n"))
        assertEquals(Kind.MEDIA, facts.kind)
        assertNull(facts.durationSec)
        assertEquals(true, facts.live)
    }

    @Test
    fun `a byte order mark and leading blank lines are tolerated`() {
        assertEquals(Kind.MEDIA, parseHls("﻿\r\n\r\n#EXTM3U\r\n#EXTINF:5,\r\ns.ts\r\n#EXT-X-ENDLIST\r\n")?.kind)
    }

    @Test
    fun `text that is not a playlist is not one`() {
        assertNull(parseHls("<html><body>blocked</body></html>"))
        assertNull(parseHls(""))
        assertNull(parseHls("{\"error\":404}"))
    }

    @Test
    fun `a file size comes from content length or from the content range total`() {
        assertEquals(1_610_000_000L, parseFileHeaders(200, "1610000000", null))
        assertEquals(1_610_000_000L, parseFileHeaders(206, "1", "bytes 0-0/1610000000"))
        assertNull(parseFileHeaders(206, "1", "bytes 0-0/*"))
        assertNull(parseFileHeaders(200, null, null))
        assertNull(parseFileHeaders(200, "abc", null))
        assertNull(parseFileHeaders(403, "100", null))
        assertNull(parseFileHeaders(206, "1", null))
    }
}
