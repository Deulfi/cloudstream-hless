package com.lagradost.hless

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/**
 * A person's preferences (6a), carried in the addon URL as
 * `c~<base64url(json)>`. Every field is optional: missing means "the
 * server's default". The TMDB key is a server setting and never goes in here. A person's own
 * MediaFlow address and password ([mfUrl], [mfPw]) do, because the link is the person's
 * own: anyone who has the link can use that proxy.
 */
@Serializable
data class Prefs(
    val langs: List<String>? = null,
    val minH: Int? = null,
    val maxH: Int? = null,
    /** Providers never asked. */
    val off: List<String>? = null,
    /** Only these providers are asked (written instead of a long [off] list); [off] still applies. */
    val only: List<String>? = null,
    /** Providers listed first, in this order. */
    val first: List<String>? = null,
    val sort: String? = null,
    val perProv: Int? = null,
    val total: Int? = null,
    val variants: String? = null,
    val flags: String? = null,
    val icons: Boolean? = null,
    val proxy: Boolean? = null,
    /** The person's provider order (all names, in order). */
    val order: List<String>? = null,
    /** Order the search by how often providers find something. */
    val smart: Boolean? = null,
    /** Hosters switched off. */
    val hostOff: List<String>? = null,
    val hint: Boolean? = null,
    /** The person's own MediaFlow proxy, used instead of the server's. */
    val mfUrl: String? = null,
    val mfPw: String? = null,
)

private val prefsJson = Json { ignoreUnknownKeys = true; isLenient = false; encodeDefaults = false; explicitNulls = false }

private const val MAX_SEGMENT_CHARS = 8_000   // about 6 KiB once decoded
private const val MAX_BYTES = 6_144
private const val MAX_LIST = 60
private const val MAX_PROVIDER_LIST = 200
private const val MAX_NAME = 40

/**
 * Reads the part after `c~`. Anything wrong with it (too long, not base64,
 * not JSON, wrong types) gives null, never an error: the defaults apply.
 */
fun decodePrefs(segment: String?): Prefs? {
    if (segment.isNullOrEmpty() || segment.length > MAX_SEGMENT_CHARS) return null
    return try {
        val bytes = Base64.getUrlDecoder().decode(segment)
        if (bytes.size > MAX_BYTES) null else prefsJson.decodeFromString(Prefs.serializer(), String(bytes, Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }
}

fun encodePrefs(prefs: Prefs): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(prefsJson.encodeToString(Prefs.serializer(), prefs).toByteArray())

private fun names(list: List<String>?, max: Int = MAX_LIST): List<String>? =
    list?.map { it.trim().take(MAX_NAME) }?.filter { it.isNotEmpty() }?.take(max)

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
        providersDeny = (rank.providersDeny + (names(off, MAX_PROVIDER_LIST) ?: emptyList())).distinct(),
        providersAllow = narrow(rank.providersAllow, names(only, MAX_PROVIDER_LIST)),
        providersFirst = names(first) ?: rank.providersFirst,
        sort = SortMode.parse(sort) ?: rank.sort,
        maxPerProvider = perProv?.coerceIn(1, 20) ?: rank.maxPerProvider,
        maxStreams = total?.coerceIn(1, 100) ?: rank.maxStreams,
        order = names(order, 100) ?: rank.order,
        smart = smart ?: rank.smart,
        hostersOff = names(hostOff, 100) ?: rank.hostersOff,
    )
    return defaults.copy(
        rank = resolvedRank,
        display = defaults.display.copy(
            flags = FlagMode.parse(flags) ?: defaults.display.flags,
            icons = icons ?: defaults.display.icons,
        ),
        variants = Variants.parse(variants) ?: defaults.variants,
        proxy = proxy ?: defaults.proxy,
        ownProxy = ownProxy(mfUrl, mfPw),
        hint = hint ?: defaults.hint,
    )
}

/** The server's allow list can only be narrowed: the person's list is cut down to what the server allows. */
private fun narrow(server: List<String>, person: List<String>?): List<String> = when {
    person.isNullOrEmpty() -> server
    server.isEmpty() -> person
    else -> server.filter { s -> person.any { it.equals(s, ignoreCase = true) } }.ifEmpty { server }
}

/** The person's own MediaFlow, when the address is a plain http(s) address and the password is usable; else null. */
internal fun ownProxy(url: String?, password: String?): OwnProxy? {
    val base = url?.trim()?.trimEnd('/')?.takeIf { it.length in 8..200 } ?: return null
    val pw = password?.takeIf { it.isNotEmpty() && it.length <= 100 && it.none { c -> c.isISOControl() || c.isWhitespace() } } ?: return null
    val parsed = base.toHttpUrlOrNull() ?: return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) return null
    return OwnProxy(base, pw)
}
