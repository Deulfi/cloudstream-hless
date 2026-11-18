package com.lagradost.hless

import kotlinx.serialization.Serializable

/** One stream entry in a Stremio `stream` response. */
@Serializable
data class StremioStream(
    val name: String,
    val description: String,
    /** The stream; null for a note row, which has [externalUrl] instead. */
    val url: String? = null,
    val externalUrl: String? = null,
    val behaviorHints: StreamHints? = null,
    val subtitles: List<StremioSubtitle>? = null,
)

@Serializable
data class StreamHints(
    val notWebReady: Boolean? = null,
    val bingeGroup: String? = null,
    val proxyHeaders: ProxyHeaders? = null,
    /** Bytes, when a probe learned the size (Stremio uses it for subtitle matching and display). */
    val videoSize: Long? = null,
)

@Serializable
data class ProxyHeaders(val request: Map<String, String>)

@Serializable
data class StremioSubtitle(val id: String, val url: String, val lang: String)

@Serializable
data class StreamsResponse(val streams: List<StremioStream>)
