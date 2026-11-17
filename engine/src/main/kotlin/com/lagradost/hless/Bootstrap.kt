package com.lagradost.hless

import com.lagradost.macos.models.RepoPluginDto
import kotlinx.coroutines.delay

/**
 * 2b: sync the configured repositories and install the configured plugins so
 * the container needs no manual API calls. One repository failing must not
 * stop the others; nothing is ever uninstalled here.
 */
suspend fun bootstrap(client: EngineClient) {
    if (Config.repos.isEmpty()) {
        println("hless: no repositories configured")
        return
    }
    for ((index, url) in Config.repos.withIndex()) {
        if (index > 0) delay(2_000) // /repos/sync allows about one call every 2 s
        syncAndInstall(client, url)
    }
    logPlugins(client)
}

private suspend fun syncAndInstall(client: EngineClient, url: String) {
    try {
        syncRepo(client, url)
    } catch (e: Exception) {
        println("hless: repo $url failed: ${e.message}")
        return
    }
    val catalog = try {
        client.repoPlugins(url)
    } catch (e: Exception) {
        println("hless: repo $url catalog failed: ${e.message}")
        return
    }
    for (plugin in choosePlugins(catalog, Config.plugins)) {
        if (plugin.installationId != null) {
            println("hless: ${plugin.name} already installed")
            continue
        }
        try {
            installWithConsent(client, url, plugin.internalName)
            println("hless: ${plugin.name} installed")
        } catch (e: EngineException) {
            println("hless: ${plugin.name} failed: ${e.code} ${e.message}")
        } catch (e: Exception) {
            println("hless: ${plugin.name} failed: ${e.message}")
        }
    }
}

private suspend fun syncRepo(client: EngineClient, url: String) {
    try {
        client.syncRepo(url, installAll = false)
    } catch (e: EngineException) {
        if (e.code != "consent_required") throw e
        client.consent(url)
        client.syncRepo(url, installAll = false)
    }
}

/**
 * The engine gates installs behind per-repo consent, but not syncs, so the
 * consent answer can only surface here. Grant it once and retry.
 */
internal suspend fun installWithConsent(client: EngineClient, url: String, internalName: String) {
    try {
        client.install(url, internalName)
    } catch (e: EngineException) {
        if (e.code != "consent_required") throw e
        client.consent(url)
        client.install(url, internalName)
    }
}

/**
 * Step 3 of 2b: `["*"]` selects all entries; otherwise those whose
 * `internalName` equals a listed name, ignoring case.
 */
fun choosePlugins(catalog: List<RepoPluginDto>, wanted: List<String>): List<RepoPluginDto> {
    if ("*" in wanted) return catalog
    val names = wanted.map { it.lowercase() }
    return catalog.filter { it.internalName.lowercase() in names }
}

private suspend fun logPlugins(client: EngineClient) {
    val plugins = try {
        client.plugins()
    } catch (e: Exception) {
        println("hless: could not list plugins: ${e.message}")
        return
    }
    println("hless: plugins")
    for (plugin in plugins) {
        println(
            "  ${plugin.name}: state=${plugin.state}, providers=${plugin.providers.size}, lastError=${plugin.lastError}",
        )
    }
}
