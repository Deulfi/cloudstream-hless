package com.lagradost.hless

import java.util.Locale

/** Whether the variants of an HLS master are folded into it (5b) or listed too. */
enum class Variants {
    FOLD, ALL;

    companion object {
        fun parse(value: String?): Variants? = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "fold" -> FOLD
            "all" -> ALL
            else -> null
        }
    }
}

/**
 * Everything that shapes one answer. The server's defaults come from the
 * environment ([fromConfig]); a person's preferences (6a) are laid over them
 * per request.
 */
/** A MediaFlow address (no trailing slash) and its password. */
data class OwnProxy(val url: String, val password: String)

data class Settings(
    val rank: RankSettings = RankSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val variants: Variants = Variants.FOLD,
    /** Wrap links in MediaFlow when the server has one. */
    val proxy: Boolean = true,
    /** The person's own MediaFlow, used instead of the server's when set. */
    val ownProxy: OwnProxy? = null,
    /** Add a note to the list when results are still loading or were hidden by a filter. */
    val hint: Boolean = true,
) {
    companion object {
        fun fromConfig(): Settings = Settings(
            rank = RankSettings(
                langs = Config.langs,
                minQuality = Config.minQuality,
                maxQuality = Config.maxQuality,
                maxPerProvider = Config.maxPerProvider,
                maxStreams = Config.maxStreams,
                providersDeny = Config.providersDeny,
                providersAllow = Config.providersAllow,
                providersFirst = Config.providersFirst,
                sort = Config.sort,
            ),
            display = DisplaySettings(label = Config.label, flags = Config.flags, icons = Config.icons),
            variants = Config.variants,
            hint = Config.hint,
        )
    }
}
