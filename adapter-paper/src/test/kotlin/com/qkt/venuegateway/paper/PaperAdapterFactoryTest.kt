package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.TradeMode
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperAdapterFactoryTest {
    private val servers = CopyOnWriteArrayList<HttpServer>()

    @AfterEach
    fun stop() = servers.forEach { it.stop(0) }

    /** A local server that answers every request 503 with no body, writing each request's path and query to [heard]. */
    private fun down(heard: MutableList<String>): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            heard += exchange.requestURI.toString()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `the factory is the paper type and builds an adapter reading listings, history and tickers where it is told`(
        @TempDir dir: Path,
    ) {
        val market = CopyOnWriteArrayList<String>()
        val history = CopyOnWriteArrayList<String>()
        val ws = CopyOnWriteArrayList<String>()
        val settings =
            mapOf(
                "deribit_url" to down(market),
                "deribit_history_url" to down(history),
                "deribit_ws_url" to down(ws).replace("http", "ws") + "/ws/api/v2",
            )
        val factory = PaperAdapterFactory()
        val adapter = factory.create(AdapterContext(settings, { 1_790_928_000_000L }, dir))

        assertThat(factory.type).isEqualTo("paper")
        assertThat(adapter.id).isEqualTo("paper")
        assertThatThrownBy { adapter.instruments() }.isInstanceOf(VenueUnavailableException::class.java)
        assertThat(market).anyMatch { it.startsWith("/api/v2/public/get_instruments?currency=USDC") }
        assertThat(history).isEmpty()

        assertThatThrownBy { adapter.trades("BTC_USDC-PERPETUAL", 0, 60_000, 10) }
            .isInstanceOf(VenueUnavailableException::class.java)
        assertThat(history).anyMatch { it.startsWith("/api/v2/public/") && "BTC_USDC-PERPETUAL" in it }

        adapter.connect(HeardListener(CopyOnWriteArrayList()))
        await { ws.isNotEmpty() }
        assertThat(ws.first()).isEqualTo("/ws/api/v2")
        adapter.close()
    }

    @Test
    fun `the paper venue reports its login in demo mode and refuses to modify an order`(
        @TempDir dir: Path,
    ) {
        val adapter =
            PaperAdapterFactory().create(
                AdapterContext(mapOf("login" to "desk-7", "currency" to "USDT"), { 0L }, dir),
            )

        val identity = adapter.identity()
        assertThat(identity.login).isEqualTo("desk-7")
        assertThat(identity.mode).isEqualTo(TradeMode.DEMO)
        assertThat(adapter.account().currency).isEqualTo("USDT")
        assertThat(adapter.account().balance).isEqualByComparingTo(BigDecimal("10000"))
        assertThatThrownBy { adapter.modify("a-1", OrderChange(quantity = BigDecimal.ONE)) }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("cancel and place again")
        adapter.close()
    }
}
