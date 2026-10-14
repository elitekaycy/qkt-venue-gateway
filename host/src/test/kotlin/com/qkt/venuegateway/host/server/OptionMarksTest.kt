package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.vgp.WireHealth
import com.qkt.vgp.WireQuote
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `option_marks` (wire spec §1, §4a): declared in health, and an option quote's mark IV and forward reach the socket. */
class OptionMarksTest {
    @TempDir lateinit var dir: Path
    private val venue = FakeAdapter()
    private val http = OkHttpClient()
    private val now = 600_000L
    private val option = "BTC_USDC-9OCT26-82000-P"
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""

    private fun start() {
        gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { now },
                mapOf(Role.TRADER to "t"),
            ) { now }
        gateway.start()
        venue.listener!!.connection(true, "test")
        hub = QuoteHub(gateway, refreshMs = 100).also { it.start() }
        server = GatewayServer(gateway, hub, "127.0.0.1", 0)
        base = "http://127.0.0.1:${server.start()}"
    }

    @AfterEach
    fun stop() {
        server.close()
        hub.close()
        gateway.close()
    }

    private fun request(url: String) =
        Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer t")
            .build()

    @Test
    fun `health names option marks when the adapter declares them`() {
        venue.capabilities = setOf(Capability.QUOTES, Capability.OPTION_MARKS)
        start()

        val body = http.newCall(request("$base/v1/health")).execute().use { it.body!!.string() }

        assertThat(wireJson.decodeFromString(WireHealth.serializer(), body).capabilities)
            .containsExactly("quotes", "option_marks")
    }

    @Test
    fun `an option quote's mark iv and forward reach the quotes socket as the venue reported them`() {
        start()
        val received = LinkedBlockingQueue<String>()
        http.newWebSocket(
            request(base.replace("http", "ws") + "/v1/quotes?symbols=$option"),
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    received += text
                }
            },
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (venue.subscriptions.none { option in it.first }) {
            check(System.currentTimeMillis() < deadline) { "never subscribed" }
            Thread.sleep(10)
        }

        venue.listener!!.quote(
            VenueQuote(
                option,
                mark = BigDecimal("648.50"),
                markIv = BigDecimal("52.30"),
                underlying = BigDecimal("84437.55"),
                timeMs = now,
            ),
        )

        val text = received.poll(5, TimeUnit.SECONDS) ?: error("no quote of $option")
        val quote = wireJson.decodeFromString(WireQuote.serializer(), text)
        assertThat(quote.symbol).isEqualTo(option)
        assertThat(quote.markIv).isEqualTo("52.30")
        assertThat(quote.underlying).isEqualTo("84437.55")
    }
}
