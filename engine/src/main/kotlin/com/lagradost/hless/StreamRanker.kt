package com.lagradost.hless

/** One ranked stream with the metadata the ranker needs. */
data class Candidate(
    val stream: StremioStream,
    val providerName: String,
    val providerLanguage: String?,
    /** Height in pixels; <= 0 is unknown. */
    val quality: Int,
    /** The original link URL, before MediaFlow. */
    val url: String,
)

/** The HLESS_* ordering settings, one constructor so tests can build them. */
data class RankSettings(
    val langs: List<String> = emptyList(),
    val minQuality: Int = 0,
    val maxPerProvider: Int = 4,
    val maxStreams: Int = 30,
    val providersDeny: List<String> = emptyList(),
    val providersAllow: List<String> = emptyList(),
    val providersFirst: List<String> = emptyList(),
)

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
 * 2g: drop by minQuality, dedupe by URL (keep first), sort by provider-first,
 * language, quality (unknown last), provider name, cap per provider while
 * walking the sorted list, cut at maxStreams.
 */
fun rank(candidates: List<Candidate>, settings: RankSettings): List<StremioStream> {
    val deduped = candidates
        .filter { it.quality !in 1 until settings.minQuality }
        .distinctBy { it.url }
    val sorted = deduped.sortedWith(
        compareBy<Candidate> { firstIndex(it.providerName, settings.providersFirst) }
            .thenBy { langIndex(it.providerLanguage, settings.langs) }
            .thenByDescending { it.quality }
            .thenBy { it.providerName },
    )
    val kept = HashMap<String, Int>()
    val out = ArrayList<StremioStream>()
    for (candidate in sorted) {
        if (out.size >= settings.maxStreams) break
        val count = kept[candidate.providerName] ?: 0
        if (count >= settings.maxPerProvider) continue
        kept[candidate.providerName] = count + 1
        out += candidate.stream
    }
    return out
}

private fun firstIndex(name: String, first: List<String>): Int {
    val index = first.indexOfFirst { it.equals(name, ignoreCase = true) }
    return if (index >= 0) index else first.size
}

private fun langIndex(language: String?, langs: List<String>): Int {
    if (language == null) return langs.size
    val index = langs.indexOfFirst { it.equals(language, ignoreCase = true) }
    return if (index >= 0) index else langs.size
}
