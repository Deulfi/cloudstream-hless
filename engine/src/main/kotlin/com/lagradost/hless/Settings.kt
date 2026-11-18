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
data class Settings(
    val rank: RankSettings = RankSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val variants: Variants = Variants.FOLD,
    /** Wrap links in MediaFlow when the server has one. */
    val proxy: Boolean = true,
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
        )
    }
}
