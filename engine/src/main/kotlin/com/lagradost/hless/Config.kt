package com.lagradost.hless

/**
 * Parsed hless configuration. One constructor so tests can pass a fixed env
 * map; [Config] delegates to it with the real environment.
 */
class ConfigValues(env: Map<String, String>) {
    val port: Int
    val enginePort: Int
    val secret: String?
    val publicUrl: String?
    val repos: List<String>
    val plugins: List<String>
    val deadlineMs: Long
    val mediaflowUrl: String?
    val mediaflowPassword: String?
    val tmdbKey: String?
    val langs: List<String>
    val minQuality: Int
    val maxPerProvider: Int
    val maxStreams: Int
    val providersDeny: List<String>
    val providersAllow: List<String>
    val providersFirst: List<String>
    val animeAudio: String
    val solverUrl: String?
    val solverTimeoutMs: Long

    init {
        port = intVar(env, "HLESS_PORT", 7000)
        enginePort = intVar(env, "HLESS_ENGINE_PORT", 17171)
        secret = strVar(env, "HLESS_SECRET")
        publicUrl = strVar(env, "HLESS_PUBLIC_URL")?.let(::stripTrailingSlash)
        repos = listVar(env, "HLESS_REPOS", emptyList()).map(::stripTrailingSlash)
        plugins = listVar(env, "HLESS_PLUGINS", listOf("*"))
        deadlineMs = longVar(env, "HLESS_DEADLINE_MS", 15_000)
        mediaflowUrl = strVar(env, "HLESS_MEDIAFLOW_URL")?.let(::stripTrailingSlash)
        mediaflowPassword = strVar(env, "HLESS_MEDIAFLOW_PASSWORD")
        tmdbKey = strVar(env, "HLESS_TMDB_KEY")
        langs = listVar(env, "HLESS_LANGS", listOf("en"))
        minQuality = intVar(env, "HLESS_MIN_QUALITY", 0)
        maxPerProvider = intVar(env, "HLESS_MAX_PER_PROVIDER", 4)
        maxStreams = intVar(env, "HLESS_MAX_STREAMS", 30)
        providersDeny = listVar(env, "HLESS_PROVIDERS_DENY", emptyList())
        providersAllow = listVar(env, "HLESS_PROVIDERS_ALLOW", emptyList())
        providersFirst = listVar(env, "HLESS_PROVIDERS_FIRST", emptyList())
        animeAudio = strVar(env, "HLESS_ANIME_AUDIO") ?: "sub"
        solverUrl = strVar(env, "HLESS_SOLVER_URL")?.let(::stripTrailingSlash)
        solverTimeoutMs = longVar(env, "HLESS_SOLVER_TIMEOUT_MS", 60_000)
    }

    private fun raw(env: Map<String, String>, name: String): String? =
        env[name]?.trim()?.takeIf { it.isNotEmpty() }

    private fun strVar(env: Map<String, String>, name: String): String? = raw(env, name)

    private fun intVar(env: Map<String, String>, name: String, default: Int): Int {
        val value = raw(env, name) ?: return default
        return value.toIntOrNull() ?: error("$name must be an integer, got '$value'")
    }

    private fun longVar(env: Map<String, String>, name: String, default: Long): Long {
        val value = raw(env, name) ?: return default
        return value.toLongOrNull() ?: error("$name must be a number, got '$value'")
    }

    private fun listVar(env: Map<String, String>, name: String, default: List<String>): List<String> {
        val value = raw(env, name) ?: return default
        return value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun stripTrailingSlash(value: String): String = value.removeSuffix("/")
}

/** Process-wide configuration, read once from the environment. */
object Config {
    private val values = ConfigValues(System.getenv())

    val port: Int get() = values.port
    val enginePort: Int get() = values.enginePort
    val secret: String? get() = values.secret
    val publicUrl: String? get() = values.publicUrl
    val repos: List<String> get() = values.repos
    val plugins: List<String> get() = values.plugins
    val deadlineMs: Long get() = values.deadlineMs
    val mediaflowUrl: String? get() = values.mediaflowUrl
    val mediaflowPassword: String? get() = values.mediaflowPassword
    val tmdbKey: String? get() = values.tmdbKey
    val langs: List<String> get() = values.langs
    val minQuality: Int get() = values.minQuality
    val maxPerProvider: Int get() = values.maxPerProvider
    val maxStreams: Int get() = values.maxStreams
    val providersDeny: List<String> get() = values.providersDeny
    val providersAllow: List<String> get() = values.providersAllow
    val providersFirst: List<String> get() = values.providersFirst
    val animeAudio: String get() = values.animeAudio
    val solverUrl: String? get() = values.solverUrl
    val solverTimeoutMs: Long get() = values.solverTimeoutMs
}
