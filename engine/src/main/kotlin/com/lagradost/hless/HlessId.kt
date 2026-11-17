package com.lagradost.hless

import java.util.Base64

/**
 * Our addon ids (3b): `hless:<b64url(providerId)>:<b64url(url)>`, with
 * `:<season>:<episode>` appended for an episode. Base64url without padding,
 * so an encoded id contains none of `:`, `/`, `+`, `=` or spaces.
 */
data class HlessId(
    val providerId: String,
    val url: String,
    val season: Int? = null,
    val episode: Int? = null,
) {
    fun encode(): String = buildString {
        append("hless:")
            .append(b64url(providerId))
            .append(':')
            .append(b64url(url))
        if (season != null || episode != null) {
            append(':').append(season ?: 1).append(':').append(episode ?: 1)
        }
    }

    companion object {
        /** Decodes an id, or null when it is not a well-formed hless id. */
        fun decode(id: String): HlessId? {
            if (!id.startsWith("hless:")) return null
            val parts = id.removePrefix("hless:").split(":")
            if (parts.size != 2 && parts.size != 4) return null
            val providerId = b64urlDecode(parts[0]) ?: return null
            val url = b64urlDecode(parts[1]) ?: return null
            if (providerId.isEmpty() || url.isEmpty()) return null
            val season = if (parts.size == 4) parts[2].toIntOrNull() else null
            val episode = if (parts.size == 4) parts[3].toIntOrNull() else null
            if (parts.size == 4 && (season == null || episode == null)) return null
            return HlessId(providerId, url, season, episode)
        }

        fun b64url(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(Charsets.UTF_8))

        fun b64urlDecode(value: String): String? = try {
            Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
