package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.market.QuoteHub
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/** The gateway's HTTP and WebSocket server on [host]:[port] (`0` picks a free port, as tests do). */
class GatewayServer(
    gateway: Gateway,
    hub: QuoteHub,
    host: String,
    port: Int,
) : AutoCloseable {
    private val server = embeddedServer(Netty, port = port, host = host) { gatewayModule(gateway, hub) }

    /** Starts serving; returns the port it listens on. */
    fun start(): Int {
        server.start(wait = false)
        return runBlocking {
            server.engine
                .resolvedConnectors()
                .first()
                .port
        }
    }

    override fun close() = server.stop(GRACE_MS, TIMEOUT_MS)

    private companion object {
        const val GRACE_MS = 500L
        const val TIMEOUT_MS = 5_000L
    }
}

/** Every VGP v1 route of [gateway], its quotes from [hub]. */
fun Application.gatewayModule(
    gateway: Gateway,
    hub: QuoteHub,
) {
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
    }
    routing {
        restRoutes(gateway)
        fundingRoutes(gateway)
        streamRoute(gateway)
        marketRoutes(gateway, hub)
    }
}
