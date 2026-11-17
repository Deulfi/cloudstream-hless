package com.lagradost.hless

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KitsuTest {
    private fun attributes(fixture: String): JsonObject =
        Json.parseToJsonElement(fixture).jsonObject

    @Test
    fun `names come from en, en_jp, canonicalTitle`() {
        val info = Kitsu.parse(attributes(
            """{"titles":{"en":"One Piece","en_jp":"Wan Piisu"},"canonicalTitle":"One Piece (TV)","startDate":"1999-10-20"}"""
        ))
        assertEquals(listOf("One Piece", "Wan Piisu", "One Piece (TV)"), info?.names)
        assertEquals(1999, info?.year)
    }

    @Test
    fun `names equal by normalize are dropped`() {
        val info = Kitsu.parse(attributes(
            """{"titles":{"en":"Cowboy Bebop","en_jp":"cowboy bebop"},"canonicalTitle":"COWBOY BEBOP"}"""
        ))
        assertEquals(listOf("Cowboy Bebop"), info?.names)
    }

    @Test
    fun `no usable title is null`() {
        assertNull(Kitsu.parse(attributes("""{"canonicalTitle":null}""")))
    }

    @Test
    fun `missing start date is null year`() {
        val info = Kitsu.parse(attributes("""{"titles":{"en":"X"}}"""))
        assertEquals(listOf("X"), info?.names)
        assertNull(info?.year)
    }

    @Test
    fun `kitsu disk cache serves a hit`() = runBlocking {
        val store = JsonFileStore(File(createTempDirectory("hless-kitsu").toString(), "kitsu.json"))
        val detailCalls = AtomicInteger(0)
        val lookup = KitsuLookup(
            store = store,
            detail = { _ ->
                detailCalls.incrementAndGet()
                Json.parseToJsonElement("""{"titles":{"en":"X"},"canonicalTitle":"X"}""").jsonObject
            },
        )
        assertEquals(TitleInfo(listOf("X"), null), lookup.lookup("series", "123"))
        assertEquals(1, detailCalls.get())
        // cached: detail is not called again
        assertEquals(TitleInfo(listOf("X"), null), lookup.lookup("series", "123"))
        assertEquals(1, detailCalls.get())
    }
}
