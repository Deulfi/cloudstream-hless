package com.lagradost.hless

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogoTest {
    private val image = assertNotNull(ImageIO.read(ByteArrayInputStream(Logo.png)))

    private fun alpha(x: Int, y: Int) = (image.getRGB(x, y) ushr 24) and 0xFF
    private fun white(x: Int, y: Int) = (image.getRGB(x, y) and 0xFFFFFF) == 0xFFFFFF && alpha(x, y) == 255

    @Test
    fun `the icon is a square png`() {
        assertEquals(256, image.width)
        assertEquals(256, image.height)
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), Logo.png.take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `the corners are rounded and transparent, the inside is dark`() {
        assertEquals(0, alpha(0, 0))
        assertEquals(255, alpha(128, 20))
        assertTrue(!white(20, 128))
    }

    @Test
    fun `it shows a body and legs, and no head`() {
        assertTrue(white(128, 120), "torso")
        assertTrue(white(106, 112), "left arm")
        assertTrue(white(150, 112), "right arm")
        assertTrue(white(112, 177), "left leg")
        assertTrue(white(144, 177), "right leg")
        // above the neck there is nothing: where a head would be, the background shows
        assertTrue(!white(128, 40), "a head")
        assertTrue(!white(128, 52), "a head")
    }
}
