package io.github.youndie.kesh.server

import io.github.youndie.kore.lifecycle.runUntilSignal
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main() {
    val config = ServerConfig.fromEnvironment()
    runBlocking {
        val server = KeshServer(config)
        try {
            server.start()
        } catch (e: StartupFailure) {
            println("kesh: ${e.message}")
            exitProcess(1)
        }
        println("kesh: listening on ${config.host}:${server.port}, maxclients ${server.maxClients}")

        // After the listener is serving, as kore requires: a signal that arrived earlier would run a
        // plan with nothing to drain. The plan grows as the items land — announce (B-15), save (B-14).
        runUntilSignal(onFinished = { run -> println(run.transcript) }) {
            drain(server)
        }
        server.close()
    }
}
