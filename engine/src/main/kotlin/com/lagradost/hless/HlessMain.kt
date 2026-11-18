package com.lagradost.hless

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom

fun main() {
    // The engine is started in-process with its own bearer token, handed over
    // via argv (SEC-02 warns but accepts; stdin is for external supervisors).
    val token = ByteArray(32)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    Thread({
        com.lagradost.macos.main(arrayOf(Config.enginePort.toString(), token))
    }, "engine").apply { isDaemon = true }.start()

    val client = OkHttpEngineClient("http://127.0.0.1:${Config.enginePort}", token)
    val titles = TitleSource()
    Runtime.getRuntime().addShutdownHook(Thread({ HlessStores.shutdown() }, "hless-shutdown"))

    try {
        runBlocking { client.awaitReady(timeoutMs = 120_000) }
    } catch (e: Exception) {
        System.err.println("hless: engine not ready: ${e.message}")
        kotlin.system.exitProcess(1)
    }
    println("hless: engine ready on 127.0.0.1:${Config.enginePort}")

    // Background work lives in its own scope: the addon server below blocks this thread,
    // and a coroutine started in runBlocking could never run again once it does.
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    Config.solverUrl?.let { url ->
        println("hless: solver loop started ($url)")
        SolverLoop(client, url).start(background)
    }
    // The server answers at once, also on a first start that still has extensions to
    // install; those providers simply appear when they are ready.
    background.launch { bootstrap(client) }
    startAddonServer(StreamServiceImpl(client, titles), client)
}
