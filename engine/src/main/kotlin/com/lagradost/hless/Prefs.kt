package com.lagradost.hless

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * A person's preferences (6a), carried in the addon URL as
 * `c~<base64url(json)>`. Every field is optional: missing means "the
 * server's default". Never put a secret in here; the MediaFlow password and
 * the TMDB key are server settings.
 */
@Serializable
data class Prefs(
    val langs: List<String>? = null,
    val minH: Int? = null,
    val maxH: Int? = null,
    /** Providers never asked. */
    val off: List<String>? = null,
    /** Providers listed first, in this order. */
    val first: List<String>? = null,
    val sort: String? = null,
    val perProv: Int? = null,
    val total: Int? = null,
    val variants: String? = null,
    val flags: String? = null,
    val icons: Boolean? = null,
    val proxy: Boolean? = null,
)

private val prefsJson = Json { ignoreUnknownKeys = true; isLenient = false; encodeDefaults = false; explicitNulls = false }

private const val MAX_SEGMENT_CHARS = 3_000   // about 2 KiB once decoded
private const val MAX_LIST = 60
private const val MAX_NAME = 40

/**
 * Reads the part after `c~`. Anything wrong with it (too long, not base64,
 * not JSON, wrong types) gives null, never an error: the defaults apply.
 */
fun decodePrefs(segment: String?): Prefs? {
    if (segment.isNullOrEmpty() || segment.length > MAX_SEGMENT_CHARS) return null
    return try {
        val bytes = Base64.getUrlDecoder().decode(segment)
        if (bytes.size > 2_048) null else prefsJson.decodeFromString(Prefs.serializer(), String(bytes, Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }
}

fun encodePrefs(prefs: Prefs): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(prefsJson.encodeToString(Prefs.serializer(), prefs).toByteArray())

private fun names(list: List<String>?): List<String>? =
    list?.map { it.trim().take(MAX_NAME) }?.filter { it.isNotEmpty() }?.take(MAX_LIST)

/** The server's [defaults] with these preferences laid over them. Values are clamped; nothing here can widen what the server allows. */
fun Prefs?.resolve(defaults: Settings): Settings {
    if (this == null) return defaults
    val rank = defaults.rank
    val chosenLangs = langs?.mapNotNull { languageCode(it) }?.distinct()?.take(20)
        ?.takeIf { it.isNotEmpty() } ?: rank.langs
    val min = minH?.coerceIn(0, 4_320) ?: rank.minQuality
    var max = maxH?.coerceIn(0, 4_320) ?: rank.maxQuality
    if (min > 0 && max in 1 until min) max = 0
    val resolvedRank = rank.copy(
        langs = chosenLangs,
        minQuality = min,
        maxQuality = max,
        // The server's own deny list stays; a person can only add to it.
        providersDeny = (rank.providersDeny + (names(off) ?: emptyList())).distinct(),
        providersFirst = names(first) ?: rank.providersFirst,
        sort = SortMode.parse(sort) ?: rank.sort,
        maxPerProvider = perProv?.coerceIn(1, 20) ?: rank.maxPerProvider,
        maxStreams = total?.coerceIn(1, 100) ?: rank.maxStreams,
    )
    return defaults.copy(
        rank = resolvedRank,
        display = defaults.display.copy(
            flags = FlagMode.parse(flags) ?: defaults.display.flags,
            icons = icons ?: defaults.display.icons,
        ),
        variants = Variants.parse(variants) ?: defaults.variants,
        proxy = proxy ?: defaults.proxy,
    )
}
