package com.qkt.venuegateway

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.config.GatewayConfig
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.Reconciler
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.venuegateway.host.server.GatewayServer
import org.slf4j.LoggerFactory

/** `qkt-venue-gateway`: one account's gateway, configured by `GATEWAY_*` variables, until it is stopped. */
fun main(args: Array<String>) {
    require(args.isEmpty()) { "qkt-venue-gateway takes no arguments: it is configured by GATEWAY_* variables" }
    val config = GatewayConfig.fromEnv(System.getenv())
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
    config: GatewayConfig,
    clock: () -> Long = System::currentTimeMillis,
): RunningGateway {
    val log = LoggerFactory.getLogger("com.qkt.venuegateway.Main")
    val factory = AdapterLoader(config.pluginsDir).factory(config.adapter)
    val context = AdapterContext(config.settings, clock, config.stateDir.resolve("adapter"), config.credentials)
    val adapter =
        runCatching { factory.create(context) }.getOrElse {
            throw IllegalStateException(
                "adapter ${config.adapter}: ${it.message} " +
                    "(a setting <key> is ${GatewayConfig.SETTING_PREFIX}<KEY>; credentials are GATEWAY_LOGIN and GATEWAY_SECRET)",
                it,
            )
        }
    val gateway =
        Gateway(
            adapter,
            Journal.open(config.stateDir.resolve("journal.db")),
            InstrumentShelf.open(config.stateDir.resolve("instruments.db"), clock = clock),
            config.tokens,
            clock,
        )
    val hub = QuoteHub(gateway).also { it.start() }
    gateway.start()
    val reconciler = Reconciler(gateway).also { it.start() }
    val server = GatewayServer(gateway, hub, config.host, config.port)
    val port = server.start()
    log.info(
        "qkt-venue-gateway serving {} ({} {}) on {}:{}",
        config.adapter,
        adapter.id,
        adapter.version,
        config.host,
        port,
    )
    return RunningGateway(port, listOf(server, reconciler, hub, gateway))
}

/** A started gateway listening on [port]; closing it stops every part, last started first. */
class RunningGateway(
    val port: Int,
    private val parts: List<AutoCloseable>,
) : AutoCloseable {
    override fun close() = parts.forEach { runCatching { it.close() } }
}
