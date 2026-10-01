package com.qkt.venued

import com.qkt.venued.adapter.AdapterContext
import com.qkt.venued.config.VenuedConfig
import com.qkt.venued.host.Gateway
import com.qkt.venued.host.Reconciler
import com.qkt.venued.host.journal.Journal
import com.qkt.venued.host.market.QuoteHub
import com.qkt.venued.host.server.GatewayServer
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** `qkt-venued <config.yaml>`: one account's gateway, until the process is stopped. */
fun main(args: Array<String>) {
    val config = VenuedConfig.parse(Files.readString(Path.of(args.firstOrNull() ?: "config.yaml")), System.getenv())
    val running = start(config)
    Runtime.getRuntime().addShutdownHook(Thread { running.close() })
    Thread.currentThread().join()
}

/**
 * Starts the gateway [config] describes: the adapter (built in, or from a plugin jar), the journal in the
 * state directory, the quote hub, the venue link, the reconciler and the server. Closing the result stops
 * them in reverse order.
 */
fun start(
    config: VenuedConfig,
    clock: () -> Long = System::currentTimeMillis,
): RunningGateway {
    val log = LoggerFactory.getLogger("com.qkt.venued.Main")
    val factory = AdapterLoader(config.pluginsDir).factory(config.adapter)
    val adapter = factory.create(AdapterContext(config.settings, clock, config.stateDir.resolve("adapter")))
    val gateway = Gateway(adapter, Journal.open(config.stateDir.resolve("journal.db")), config.tokens, clock)
    val hub = QuoteHub(gateway).also { it.start() }
    gateway.start()
    val reconciler = Reconciler(gateway).also { it.start() }
    val server = GatewayServer(gateway, hub, config.host, config.port)
    val port = server.start()
    log.info("qkt-venued serving {} ({} {}) on {}:{}", config.adapter, adapter.id, adapter.version, config.host, port)
    return RunningGateway(port, listOf(server, reconciler, hub, gateway))
}

/** A started gateway listening on [port]; closing it stops every part, last started first. */
class RunningGateway(
    val port: Int,
    private val parts: List<AutoCloseable>,
) : AutoCloseable {
    override fun close() = parts.forEach { runCatching { it.close() } }
}
