package com.lagradost.hless

import java.util.Locale

/** The CloudStream library's `Qualities.Unknown`: a placeholder, not a resolution. */
const val UNKNOWN_QUALITY = 400

/** Height in pixels, or null when the link does not say. 0, negatives and 400 mean unknown. */
fun knownHeight(quality: Int): Int? = quality.takeIf { it > 0 && it != UNKNOWN_QUALITY }

/** How a language is shown next to the quality (5a). */
enum class FlagMode {
    EMOJI, CODE, NONE;

    companion object {
        fun parse(value: String?): FlagMode? = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "emoji" -> EMOJI
            "code" -> CODE
            "none" -> NONE
            else -> null
        }
    }
}

/** The look of a stream entry; one value per request (6a), defaults from the environment. */
data class DisplaySettings(
    val label: String = "大 Hless",
    val flags: FlagMode = FlagMode.EMOJI,
    val icons: Boolean = true,
)

/** Everything one stream entry shows (5a). */
data class DisplayData(
    val title: String,
    /** Known heights, highest first, distinct; empty when none is known. */
    val heights: List<Int>,
    /** The engine link type: M3U8, DASH or VIDEO. */
    val type: String,
    val provider: String,
    val source: String,
    val language: String?,
    val sizeBytes: Long?,
    val bitrateKbps: Int?,
    val subtitleLanguages: List<String>,
)

/**
 * Language to country, an explicit table. A language code is not a country
 * code (`ja` is not Japan's `JP`), so nothing is computed from the letters.
 */
private val LANGUAGE_COUNTRY = mapOf(
    "en" to "GB", "de" to "DE", "fr" to "FR", "es" to "ES", "it" to "IT", "pt" to "PT",
    "nl" to "NL", "pl" to "PL", "ru" to "RU", "tr" to "TR", "ja" to "JP", "ko" to "KR",
    "zh" to "CN", "ar" to "SA", "hi" to "IN", "sv" to "SE", "da" to "DK", "no" to "NO",
    "nb" to "NO", "fi" to "FI", "cs" to "CZ", "el" to "GR", "he" to "IL", "uk" to "UA",
    "hu" to "HU", "ro" to "RO",
)

private fun countryFlag(country: String): String {
    val a = 0x1F1E6 + (country[0] - 'A')
    val b = 0x1F1E6 + (country[1] - 'A')
    return String(Character.toChars(a)) + String(Character.toChars(b))
}

/** Lower-case language code without a region (`pt-BR` -> `pt`), or null when it is not 2-3 letters. */
fun languageCode(language: String?): String? {
    val base = language?.trim()?.lowercase(Locale.ROOT)?.split('-', '_')?.firstOrNull() ?: return null
    return base.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }
}

/** The language as a flag, as upper-case letters, or nothing, per [mode]. Unknown codes are never turned into a guessed flag. */
fun languageLabel(language: String?, mode: FlagMode): String? {
    val code = languageCode(language) ?: return null
    return when (mode) {
        FlagMode.NONE -> null
        FlagMode.CODE -> code.uppercase(Locale.ROOT)
        FlagMode.EMOJI -> LANGUAGE_COUNTRY[code]?.let(::countryFlag) ?: code.uppercase(Locale.ROOT)
    }
}

/** Cuts [text] to [max] characters with an ellipsis, without splitting a surrogate pair. */
fun truncate(text: String, max: Int): String {
    if (text.length <= max) return text
    var end = max - 1
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end.coerceAtLeast(0)) + "…"
}

/** `1.01 GB`, `963 MB`. Decimal units, as file hosts and the other bridges report them. */
fun formatSize(bytes: Long): String =
    if (bytes < 1_000_000_000L) "${(bytes + 500_000L) / 1_000_000L} MB"
    else String.format(Locale.ROOT, "%.2f GB", bytes / 1e9)

/** `~2.6 Mbps`. */
fun formatMbps(kbps: Int): String = String.format(Locale.ROOT, "~%.1f Mbps", kbps / 1000.0)

private fun typeLabel(type: String): String = when (type) {
    "M3U8" -> "HLS"
    "DASH" -> "DASH"
    else -> "Direct"
}

/** The stream name: three short lines, because Stremio's name column is about 13 characters wide. */
fun renderName(data: DisplayData, display: DisplaySettings): String {
    val quality = data.heights.maxOrNull()?.let { "${it}p" } ?: "Auto"
    val flag = languageLabel(data.language, display.flags)?.let { " $it" }.orEmpty()
    val icon = if (display.icons) "🎬 " else ""
    return listOf(display.label, "$icon$quality$flag", truncate(data.provider, 14)).joinToString("\n")
}

/** The stream description: up to five lines, the important words first; long lines are cut off by Stremio, not wrapped. */
fun renderDescription(data: DisplayData, display: DisplaySettings): String {
    fun line(icon: String, text: String) = if (display.icons) "$icon $text" else text
    val lines = ArrayList<String>(5)
    lines += line("🎬", truncate(data.title, 40))
    val qualities = data.heights.distinct().sortedDescending().joinToString(" · ") { "${it}p" }.ifEmpty { "Auto" }
    lines += line("📺", "$qualities • ${typeLabel(data.type)}")
    val source = data.source.trim().takeIf { it.isNotEmpty() && !it.equals(data.provider, ignoreCase = true) }
    lines += line("🔗", if (source != null) "${data.provider} • $source" else data.provider)
    val sizeParts = listOfNotNull(data.sizeBytes?.let(::formatSize), data.bitrateKbps?.let(::formatMbps))
    if (sizeParts.isNotEmpty()) lines += line("💾", sizeParts.joinToString(" · "))
    val subs = data.subtitleLanguages.mapNotNull(::languageCode).map { it.uppercase(Locale.ROOT) }.distinct().take(5)
    if (subs.isNotEmpty()) lines += line("🔤", subs.joinToString(" "))
    return lines.joinToString("\n")
}
