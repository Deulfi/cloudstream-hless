package com.lagradost.hless

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
}
