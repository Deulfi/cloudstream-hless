package com.lagradost.hless

import com.lagradost.macos.models.StreamLinkDto
import com.lagradost.macos.models.SubtitleDto
import java.util.Locale

/**
 * Maps one engine link to a Stremio stream, or null when the link must be
 * dropped: torrents and magnets, non-http(s) URLs, split audio/video, DRM
 * without MediaFlow.
 */
object StreamMapper {
    /** The default MediaFlow wrapper: the configured proxy URL, or null when unset. */
    val defaultMediaflow: (StreamLinkDto) -> String? = MediaFlow::wrap

    fun map(
        provider: String,
        providerId: String,
        link: StreamLinkDto,
        subtitles: List<SubtitleDto>,
        providerLanguage: String?,
        mediaflow: (StreamLinkDto) -> String? = defaultMediaflow,
    ): StremioStream? {
        if (link.type == "TORRENT" || link.type == "MAGNET") return null
        if (!link.url.startsWith("http://") && !link.url.startsWith("https://")) return null
        if (link.audioTracks.isNotEmpty()) return null
        val proxy = mediaflow(link)
        if (link.drm != null && proxy == null) return null

        val qualityLabel = if (link.quality > 0) "${link.quality}p" else "?"
        val languageLabel = providerLanguage?.takeIf { it.isNotBlank() }?.let(::flagOf)
        val name = buildString {
            append("CS ")
            if (languageLabel != null) {
                append(languageLabel)
                append(' ')
            }
            append(qualityLabel)
        }
        val description = "$provider · ${link.source}\n${link.name}"
        val streamSubtitles = subtitles
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .mapIndexed { index, subtitle -> StremioSubtitle(index.toString(), subtitle.url, subtitle.language) }
        val bingeGroup = "hless|$providerId|${link.source}|${link.quality}"

        val url: String
        val hints: StreamHints
        if (proxy != null) {
            url = proxy
            hints = StreamHints(bingeGroup = bingeGroup)
        } else if (link.headers.isEmpty() && link.url.startsWith("https://")) {
            url = link.url
            hints = StreamHints(bingeGroup = bingeGroup)
        } else {
            url = link.url
            hints = StreamHints(
                notWebReady = true,
                bingeGroup = bingeGroup,
                proxyHeaders = ProxyHeaders(link.headers),
            )
        }
        return StremioStream(
            name = name,
            description = description,
            url = url,
            behaviorHints = hints,
            subtitles = streamSubtitles.ifEmpty { null },
        )
    }

    /** ISO 639-1 language code -> flag emoji, or null when not a 2-letter code. */
    private fun flagOf(code: String): String? {
        val lang = code.trim().lowercase(Locale.ROOT)
        if (lang.length != 2) return null
        // 'en' has no single flag; GB is the conventional choice. Other codes map
        // directly to their region-indicator (country) flag.
        val letters = if (lang == "en") "gb" else lang
        val a = letters[0].code - 'a'.code
        val b = letters[1].code - 'a'.code
        if (a !in 0..25 || b !in 0..25) return null
        // Region indicator symbols U+1F1E6..U+1F1FF map to a..z.
        return String(Character.toChars(0x1F1E6 + a)) + String(Character.toChars(0x1F1E6 + b))
    }
}
