package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class QuoteChangesRoutesTest {
    @TempDir lateinit var dir: Path
    private val venue = FakeAdapter()
    private val http = OkHttpClient()
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""

    @BeforeEach
    fun start() {
        val shelf = InstrumentShelf.open(dir.resolve("i.db")) { 1_000L }
        gateway = Gateway(venue, Journal.open(dir.resolve("j.db")), shelf, mapOf(Role.TRADER to "t")) { 1_000L }
        gateway.start()
        venue.listener!!.connection(true, "test")
        hub = QuoteHub(gateway, batchMs = 20).also { it.start() }
        server = GatewayServer(gateway, hub, "127.0.0.1", 0)
        base = "http://127.0.0.1:${server.start()}"
    }

    @AfterEach
    fun stop() {
        server.close()
        hub.close()
        gateway.close()
    }

    /** The messages of a quote socket opened with [query]; a close is recorded as `closed <code> <reason>`. */
    private fun quotes(query: String): LinkedBlockingQueue<String> {
        val messages = LinkedBlockingQueue<String>()
        val request =
            Request
                .Builder()
                .url(base.replace("http", "ws") + "/v1/quotes?$query")
                .header("Authorization", "Bearer t")
                .build()
        http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    messages += text
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    messages += "closed $code $reason"
                }
            },
        )
        return messages
    }

    private fun awaitSubscribed() {
        val deadline = System.currentTimeMillis() + 5_000
        while (venue.subscriptions.none { "BTC_USDC-PERPETUAL" in it }) {
            check(System.currentTimeMillis() < deadline) { "never subscribed" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `with refresh=changes each message is a JSON array of the quotes that changed`() {
        val received = quotes("symbols=BTC_USDC-PERPETUAL&refresh=changes")
        awaitSubscribed()

        venue.listener!!.quote(VenueQuote("BTC_USDC-PERPETUAL", BigDecimal("1"), BigDecimal("2"), timeMs = 1_000))

        assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo(
            """[{"symbol":"BTC_USDC-PERPETUAL","bid":"1","ask":"2","time":1000}]""",
        )
    }

    @Test
    fun `without refresh each message is still one quote object`() {
        val received = quotes("symbols=BTC_USDC-PERPETUAL")
        awaitSubscribed()

        venue.listener!!.quote(VenueQuote("BTC_USDC-PERPETUAL", BigDecimal("1"), BigDecimal("2"), timeMs = 1_000))

        assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo(
            """{"symbol":"BTC_USDC-PERPETUAL","bid":"1","ask":"2","time":1000}""",
        )
    }

    @Test
    fun `an unknown refresh closes the socket naming the parameter`() {
        val received = quotes("symbols=BTC_USDC-PERPETUAL&refresh=sometimes")

        assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("closed 1003 refresh must be changes")
    }
}
