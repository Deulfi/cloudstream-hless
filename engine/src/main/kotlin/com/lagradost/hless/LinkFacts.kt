package com.lagradost.hless

/** What a probe found out about one link (5b). */
enum class Kind { MASTER, MEDIA, FILE }

data class LinkFacts(
    val kind: Kind,
    /** MASTER: the quality class of each variant (see [qualityClass]), highest first, distinct. Otherwise empty. */
    val heights: List<Int> = emptyList(),
    /** MASTER: the highest variant bitrate in kbit/s. */
    val topBandwidthKbps: Int? = null,
    /** MEDIA with #EXT-X-ENDLIST: the sum of #EXTINF. Otherwise null. */
    val durationSec: Double? = null,
    /** FILE: Content-Length or the total of Content-Range. */
    val sizeBytes: Long? = null,
    /** MEDIA without #EXT-X-ENDLIST. */
    val live: Boolean = false,
)

private val RESOLUTION = Regex("""[:,]RESOLUTION=(\d+)x(\d+)""")

/**
 * The usual quality name of a picture size. A wide film is often cropped
 * (1920x800 is what a "1080p" scope film really is), so the width counts as
 * much as the height: whichever reaches the higher class decides.
 */
fun qualityClass(width: Int, height: Int): Int = when {
    width >= 3400 || height >= 2000 -> 2160
    width >= 2400 || height >= 1300 -> 1440
    width >= 1800 || height >= 1000 -> 1080
    width >= 1200 || height >= 700 -> 720
    width >= 800 || height >= 450 -> 480
    width >= 600 || height >= 340 -> 360
    width >= 400 || height >= 230 -> 240
    else -> 144
}
private val BANDWIDTH = Regex("""[:,]BANDWIDTH=(\d+)""")
private val AVERAGE_BANDWIDTH = Regex("""[:,]AVERAGE-BANDWIDTH=(\d+)""")
private val EXTINF = Regex("""^#EXTINF:([0-9]+(?:\.[0-9]+)?)""")
private val CONTENT_RANGE_TOTAL = Regex("""bytes\s+\d+-\d+/(\d+)""", RegexOption.IGNORE_CASE)

/**
 * Parses an HLS playlist, or returns null when the text is not one.
 *
 * Only `#EXT-X-STREAM-INF` lines count as variants. `#EXT-X-I-FRAME-STREAM-INF`
 * lines are trick-play streams with the same resolutions and tiny
 * bandwidths; counting them would double the variants.
 */
fun parseHls(text: String): LinkFacts? {
    val body = text.trimStart('﻿', ' ', '\t', '\r', '\n')
    if (!body.startsWith("#EXTM3U")) return null
    val lines = body.lineSequence().map { it.trim() }.toList()
    val variants = lines.filter { it.startsWith("#EXT-X-STREAM-INF") }
    if (variants.isNotEmpty()) {
        val heights = variants.mapNotNull { line ->
            val size = RESOLUTION.find(line)?.groupValues ?: return@mapNotNull null
            val width = size[1].toIntOrNull() ?: return@mapNotNull null
            val height = size[2].toIntOrNull() ?: return@mapNotNull null
            qualityClass(width, height)
        }.distinct().sortedDescending()
        val bandwidths = variants.mapNotNull {
            (AVERAGE_BANDWIDTH.find(it) ?: BANDWIDTH.find(it))?.groupValues?.get(1)?.toLongOrNull()
        }
        return LinkFacts(
            kind = Kind.MASTER,
            heights = heights,
            topBandwidthKbps = bandwidths.maxOrNull()?.let { (it / 1000).toInt() },
        )
    }
    val ended = lines.any { it.startsWith("#EXT-X-ENDLIST") }
    val seconds = lines.mapNotNull { EXTINF.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }.sum()
    return LinkFacts(
        kind = Kind.MEDIA,
        durationSec = if (ended) seconds else null,
        live = !ended,
    )
}

/**
 * The size of a file from one response: `Content-Length` of a 200, or the
 * total of `Content-Range: bytes 0-0/<total>` of a 206. Null otherwise.
 */
fun parseFileHeaders(status: Int, contentLength: String?, contentRange: String?): Long? = when (status) {
    200 -> contentLength?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    206 -> contentRange?.let { CONTENT_RANGE_TOTAL.find(it)?.groupValues?.get(1)?.toLongOrNull() }
    else -> null
}
