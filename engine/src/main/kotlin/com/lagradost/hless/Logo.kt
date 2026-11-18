package com.lagradost.hless

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The addon's icon: a white stick figure without a head on a dark rounded square, drawn once with
 * Java2D (no image file to ship). Stremio shows it next to the addon's name.
 */
object Logo {
    const val SIZE = 256

    val png: ByteArray by lazy { render() }

    private fun render(): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = Color(0x1B, 0x1B, 0x2E)
            g.fill(RoundRectangle2D.Float(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), 56f, 56f))
            g.color = Color.WHITE
            g.stroke = BasicStroke(16f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            // torso, two arms from the shoulders, two legs from the hips: no head
            g.draw(Line2D.Float(128f, 66f, 128f, 150f))
            g.draw(Line2D.Float(128f, 92f, 84f, 128f))
            g.draw(Line2D.Float(128f, 92f, 172f, 128f))
            g.draw(Line2D.Float(128f, 150f, 96f, 204f))
            g.draw(Line2D.Float(128f, 150f, 160f, 204f))
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
