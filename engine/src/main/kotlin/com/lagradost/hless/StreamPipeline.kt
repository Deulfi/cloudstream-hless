package com.lagradost.hless

import com.lagradost.macos.models.StreamLinkDto
import com.lagradost.macos.models.SubtitleDto
import java.util.EnumMap

/** The probed links of one provider for one title. */
data class ProviderResult(
    val provider: ProviderRef,
    val items: List<Item>,
    val subtitles: List<SubtitleDto>,
)

/** What one request shows, and what was left out and why (5c). */
class Rendered(val streams: List<StremioStream>, val links: Int, val dropped: Map<DropReason, Int>)

/** The runtime used for bitrates: the median of the VOD media playlists seen for the title, else TMDB's. */
internal fun runtimeSeconds(results: List<ProviderResult>, fallbackMinutes: Int?): Double? {
    val seen = results.flatMap { it.items }
        .mapNotNull { it.facts?.takeIf { facts -> facts.kind == Kind.MEDIA }?.durationSec }
        .filter { it >= 600.0 }
        .sorted()
    if (seen.isNotEmpty()) return seen[seen.size / 2]
    return fallbackMinutes?.takeIf { it > 0 }?.let { it * 60.0 }
}

/**
 * Turns the probed links of all providers into the list one request shows:
 * fold variants into masters, map each link, rank. Pure, so every request can
 * use its own [settings] on the same cached results (6a).
 */
fun renderStreams(
    results: List<ProviderResult>,
    title: String,
    runtimeFallbackMinutes: Int?,
    settings: Settings,
    mediaflow: (StreamLinkDto) -> String?,
): Rendered {
    val ctx = MapContext(
        title = title,
        runtimeSec = runtimeSeconds(results, runtimeFallbackMinutes),
        display = settings.display,
        mediaflow = if (settings.proxy) mediaflow else { _ -> null },
    )
    val dropped = EnumMap<DropReason, Int>(DropReason::class.java)
    val candidates = ArrayList<Candidate>()
    var links = 0
    for (result in results) {
        links += result.items.size
        for (item in fold(result.items, settings.variants == Variants.ALL)) {
            when (val mapped = StreamMapper.map(result.provider, item, result.subtitles, ctx)) {
                is MapResult.Stream -> candidates += mapped.candidate
                is MapResult.Dropped -> dropped.merge(mapped.reason, 1, Int::plus)
            }
        }
        // Folded links are not "dropped": they are the same stream, shown once.
    }
    val ranked = rank(candidates, settings.rank)
    for ((reason, count) in ranked.dropped) dropped.merge(reason, count, Int::plus)
    return Rendered(ranked.streams, links, dropped)
}
