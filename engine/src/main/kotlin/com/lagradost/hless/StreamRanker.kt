package com.lagradost.hless

import java.util.Locale

/** Why a link did not end up in the list; counted, never shown (5c). */
enum class DropReason {
    TORRENT, NOT_HTTP, SPLIT_AUDIO, DRM_NO_MEDIAFLOW, BELOW_MIN_QUALITY, ABOVE_MAX_QUALITY, DUPLICATE, OVER_CAP,
}

/** One ranked stream with the metadata the ranker needs. */
data class Candidate(
    val stream: StremioStream,
    val providerName: String,
    val providerLanguage: String?,
    /** The highest height; <= 0 and 400 are unknown (see [knownHeight]). */
    val quality: Int,
    /** The original link URL, before MediaFlow. */
    val url: String,
    /** The lowest height of a master's variants; the same as [quality] otherwise. */
    val lowQuality: Int = quality,
    val sizeBytes: Long? = null,
)

/** The primary order of the list (6a); the provider-first list always wins before it. */
enum class SortMode {
    LANG, QUALITY, PROVIDER, SIZE;

    companion object {
        fun parse(value: String?): SortMode? = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "lang" -> LANG
            "quality" -> QUALITY
            "provider" -> PROVIDER
            "size" -> SIZE
            else -> null
        }
    }
}

/** The ordering settings, one constructor so tests can build them. */
data class RankSettings(
    val langs: List<String> = emptyList(),
    /** Hide streams whose highest known height is below this. 0 = off. Unknown heights are kept. */
    val minQuality: Int = 0,
    /** Hide streams whose lowest known height is above this. 0 = off. Unknown heights are kept. */
    val maxQuality: Int = 0,
    val maxPerProvider: Int = 6,
    val maxStreams: Int = 40,
    val providersDeny: List<String> = emptyList(),
    val providersAllow: List<String> = emptyList(),
    val providersFirst: List<String> = emptyList(),
    val sort: SortMode = SortMode.LANG,
)

class RankResult(val streams: List<StremioStream>, val dropped: Map<DropReason, Int>)

/** Whether a provider is asked at all (2e step 4, before any engine call). */
fun askProvider(name: String, settings: RankSettings): Boolean {
    val lower = name.lowercase()
    if (settings.providersDeny.any { it.lowercase() == lower }) return false
    if (settings.providersAllow.isNotEmpty() &&
        settings.providersAllow.none { it.lowercase() == lower }
    ) {
        return false
    }
    return true
}

/**
 * 2g: drop by minimum and maximum height, dedupe by URL (keep first), sort,
 * cap per provider while walking the sorted list, cut at maxStreams.
 * Unknown heights sort after known ones and are never filtered out.
 */
fun rank(candidates: List<Candidate>, settings: RankSettings): RankResult {
    val dropped = java.util.EnumMap<DropReason, Int>(DropReason::class.java)
    fun drop(reason: DropReason) {
        dropped.merge(reason, 1, Int::plus)
    }

    val filtered = ArrayList<Candidate>()
    for (candidate in candidates) {
        val top = knownHeight(candidate.quality)
        val low = knownHeight(candidate.lowQuality) ?: top
        when {
            top != null && settings.minQuality > 0 && top < settings.minQuality -> drop(DropReason.BELOW_MIN_QUALITY)
            low != null && settings.maxQuality > 0 && low > settings.maxQuality -> drop(DropReason.ABOVE_MAX_QUALITY)
            else -> filtered += candidate
        }
    }
    val seen = HashSet<String>()
    val unique = filtered.filter { candidate ->
        seen.add(candidate.url).also { if (!it) drop(DropReason.DUPLICATE) }
    }

    val sorted = unique.sortedWith(order(settings))
    val kept = HashMap<String, Int>()
    val out = ArrayList<StremioStream>()
    for (candidate in sorted) {
        val count = kept[candidate.providerName] ?: 0
        if (out.size >= settings.maxStreams || count >= settings.maxPerProvider) {
            drop(DropReason.OVER_CAP)
            continue
        }
        kept[candidate.providerName] = count + 1
        out += candidate.stream
    }
    return RankResult(out, dropped)
}

private fun order(settings: RankSettings): Comparator<Candidate> {
    val first = compareBy<Candidate> { firstIndex(it.providerName, settings.providersFirst) }
    val byLang = compareBy<Candidate> { langIndex(it.providerLanguage, settings.langs) }
    val byHeight = compareByDescending<Candidate> { knownHeight(it.quality) ?: 0 }
    val bySize = compareByDescending<Candidate> { it.sizeBytes ?: -1L }
    val byName = compareBy<Candidate> { it.providerName }
    return when (settings.sort) {
        SortMode.LANG -> first.then(byLang).then(byHeight).then(byName)
        SortMode.QUALITY -> first.then(byHeight).then(byLang).then(byName)
        SortMode.PROVIDER -> first.then(byName).then(byLang).then(byHeight)
        SortMode.SIZE -> first.then(bySize).then(byHeight).then(byLang).then(byName)
    }
}

private fun firstIndex(name: String, first: List<String>): Int {
    val index = first.indexOfFirst { it.equals(name, ignoreCase = true) }
    return if (index >= 0) index else first.size
}

private fun langIndex(language: String?, langs: List<String>): Int {
    val code = languageCode(language) ?: return langs.size
    val index = langs.indexOfFirst { languageCode(it) == code }
    return if (index >= 0) index else langs.size
}
