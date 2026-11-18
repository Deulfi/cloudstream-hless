package com.lagradost.hless

import com.lagradost.macos.models.StreamLinkDto
import com.lagradost.macos.models.SubtitleDto
import kotlin.math.roundToInt

/** Who a link came from. */
data class ProviderRef(val name: String, val id: String, val language: String?)

/** What the mapper needs besides the link itself. */
data class MapContext(
    /** The title line of the description (`Name (2026)` or `Name S01E02`). */
    val title: String,
    /** The runtime of the title in seconds when known; turns a file size into a bitrate. */
    val runtimeSec: Double?,
    val display: DisplaySettings,
    /** The MediaFlow URL for a link, or null to hand the link over as it is. */
    val mediaflow: (StreamLinkDto) -> String? = { null },
)

sealed interface MapResult {
    class Stream(val candidate: Candidate) : MapResult
    class Dropped(val reason: DropReason) : MapResult
}

/**
 * Maps one engine link to a Stremio stream, or says why it must be dropped:
 * torrents and magnets, non-http(s) URLs, split audio/video, DRM without
 * MediaFlow.
 */
object StreamMapper {
    /** The default MediaFlow wrapper: the configured proxy URL, or null when unset. */
    val defaultMediaflow: (StreamLinkDto) -> String? = MediaFlow::wrap

    fun map(provider: ProviderRef, item: Item, subtitles: List<SubtitleDto>, ctx: MapContext): MapResult {
        val link = item.link
        if (link.type == "TORRENT" || link.type == "MAGNET") return MapResult.Dropped(DropReason.TORRENT)
        if (!link.url.startsWith("http://") && !link.url.startsWith("https://")) return MapResult.Dropped(DropReason.NOT_HTTP)
        if (link.audioTracks.isNotEmpty()) return MapResult.Dropped(DropReason.SPLIT_AUDIO)
        val proxy = ctx.mediaflow(link)
        if (link.drm != null && proxy == null) return MapResult.Dropped(DropReason.DRM_NO_MEDIAFLOW)

        val facts = item.facts
        val master = facts?.takeIf { it.kind == Kind.MASTER }
        val heights = master?.heights?.takeIf { it.isNotEmpty() } ?: listOfNotNull(knownHeight(link.quality))
        val sizeBytes = facts?.takeIf { it.kind == Kind.FILE }?.sizeBytes
        val runtimeSec = ctx.runtimeSec?.takeIf { it > 0 }
        val bitrateKbps = when {
            sizeBytes != null && runtimeSec != null -> (sizeBytes * 8.0 / runtimeSec / 1000.0).roundToInt()
            else -> master?.topBandwidthKbps
        }?.takeIf { it > 0 }

        val streamSubtitles = subtitles
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .mapIndexed { index, subtitle -> StremioSubtitle(index.toString(), subtitle.url, subtitle.language) }
        val data = DisplayData(
            title = ctx.title,
            heights = heights,
            type = link.type,
            provider = provider.name,
            source = link.source,
            language = provider.language,
            sizeBytes = sizeBytes,
            bitrateKbps = bitrateKbps,
            subtitleLanguages = streamSubtitles.map { it.lang },
        )
        // The binge group uses the link's own label, not the probe's, so it stays
        // the same whether or not a probe worked.
        val bingeGroup = "hless|${provider.id}|${link.source}|${knownHeight(link.quality) ?: 0}"

        val url: String
        val hints: StreamHints
        if (proxy != null) {
            url = proxy
            hints = StreamHints(bingeGroup = bingeGroup, videoSize = sizeBytes)
        } else if (link.headers.isEmpty() && link.url.startsWith("https://")) {
            url = link.url
            hints = StreamHints(bingeGroup = bingeGroup, videoSize = sizeBytes)
        } else {
            url = link.url
            hints = StreamHints(
                notWebReady = true,
                bingeGroup = bingeGroup,
                proxyHeaders = ProxyHeaders(link.headers),
                videoSize = sizeBytes,
            )
        }
        val stream = StremioStream(
            name = renderName(data, ctx.display),
            description = renderDescription(data, ctx.display),
            url = url,
            behaviorHints = hints,
            subtitles = streamSubtitles.ifEmpty { null },
        )
        return MapResult.Stream(
            Candidate(
                stream = stream,
                providerName = provider.name,
                providerLanguage = provider.language,
                quality = heights.maxOrNull() ?: link.quality,
                url = link.url,
                lowQuality = heights.minOrNull() ?: link.quality,
                sizeBytes = sizeBytes,
            ),
        )
    }
}
