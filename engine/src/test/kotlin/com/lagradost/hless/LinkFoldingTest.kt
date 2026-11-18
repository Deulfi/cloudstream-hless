package com.lagradost.hless

import com.lagradost.macos.models.StreamLinkDto
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkFoldingTest {
    private fun link(url: String, quality: Int, source: String, type: String = "M3U8") =
        StreamLinkDto(url = url, name = source, source = source, quality = quality, type = type)

    private fun media(url: String, quality: Int, source: String = "Voe") =
        Item(link(url, quality, source), LinkFacts(Kind.MEDIA, durationSec = 8700.0))

    private fun master(url: String, heights: List<Int>, source: String = "Voe") =
        Item(link(url, 400, source), LinkFacts(Kind.MASTER, heights = heights, topBandwidthKbps = 2642))

    private fun urls(items: List<Item>) = items.map { it.link.url }

    // The real shape: a master (labelled 400), the same master's three variants, and a direct file.
    private val megakino = listOf(
        media("https://cdn.example/v800.m3u8", 800),
        media("https://cdn.example/v720.m3u8", 720),
        media("https://cdn.example/v480.m3u8", 480),
        master("https://cdn.example/master.m3u8", listOf(1080, 720, 480)),
        Item(link("https://cdn2.example/f.mp4", 400, "Voe MP4", "VIDEO"), LinkFacts(Kind.FILE, sizeBytes = 1_010_000_000L)),
    )

    @Test
    fun `variants fold into their master`() {
        assertEquals(listOf("https://cdn.example/master.m3u8", "https://cdn2.example/f.mp4"), urls(fold(megakino, false)))
    }

    @Test
    fun `keeping variants changes nothing`() {
        assertEquals(urls(megakino), urls(fold(megakino, true)))
    }

    @Test
    fun `nothing is dropped when no master was confirmed`() {
        val unprobed = megakino.map { Item(it.link, null) }
        assertEquals(urls(unprobed), urls(fold(unprobed, false)))
    }

    @Test
    fun `an unprobed variant is dropped only if its height is one of the master's`() {
        val items = listOf(
            master("https://cdn.example/m.m3u8", listOf(1080, 720)),
            Item(link("https://cdn.example/a.m3u8", 720, "Voe"), null),
            Item(link("https://cdn.example/b.m3u8", 480, "Voe"), null),
        )
        assertEquals(listOf("https://cdn.example/m.m3u8", "https://cdn.example/b.m3u8"), urls(fold(items, false)))
    }

    @Test
    fun `a separate link of a quality the master does not have is kept`() {
        val items = listOf(
            master("https://cdn.example/m.m3u8", listOf(720)),
            media("https://cdn.example/v720.m3u8", 720),
            media("https://cdn.example/v1080.m3u8", 1080),
        )
        assertEquals(listOf("https://cdn.example/m.m3u8", "https://cdn.example/v1080.m3u8"), urls(fold(items, false)))
    }

    @Test
    fun `a master without resolutions folds nothing it cannot match`() {
        val items = listOf(
            Item(link("https://cdn.example/m.m3u8", 400, "Voe"), LinkFacts(Kind.MASTER)),
            media("https://cdn.example/v720.m3u8", 720),
            media("https://cdn.example/unknown.m3u8", 400),
        )
        assertEquals(urls(items), urls(fold(items, false)))
    }

    @Test
    fun `groups are per source and host, never merged across them`() {
        val items = listOf(
            master("https://cdn.example/m.m3u8", listOf(720), source = "Voe"),
            media("https://cdn.example/other.m3u8", 720, source = "Vidara"),        // same host, other source
            media("https://elsewhere.example/v.m3u8", 720, source = "Voe"),         // same source, other host
        )
        assertEquals(urls(items), urls(fold(items, false)))
    }

    @Test
    fun `of several masters in a group the one with most variants stays`() {
        val items = listOf(
            master("https://cdn.example/small.m3u8", listOf(720)),
            master("https://cdn.example/big.m3u8", listOf(1080, 720, 480)),
            master("https://cdn.example/other.m3u8", listOf(720)),
        )
        assertEquals(listOf("https://cdn.example/big.m3u8"), urls(fold(items, false)))
    }

    @Test
    fun `direct files and lone media playlists are left alone`() {
        val items = listOf(
            Item(link("https://a.example/1.mp4", 1080, "Dood", "VIDEO"), LinkFacts(Kind.FILE, sizeBytes = 1)),
            media("https://b.example/v.m3u8", 400, "Vixeo"),
        )
        assertEquals(urls(items), urls(fold(items, false)))
    }
}
