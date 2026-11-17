package com.lagradost.hless

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

    runBlocking {
        try {
            client.awaitReady(timeoutMs = 120_000)
        } catch (e: Exception) {
            System.err.println("hless: engine not ready: ${e.message}")
            kotlin.system.exitProcess(1)
        }
        println("hless: engine ready on 127.0.0.1:${Config.enginePort}")
        bootstrap(client)
        startAddonServer(StreamServiceImpl(client, titles), client)
    }
}
