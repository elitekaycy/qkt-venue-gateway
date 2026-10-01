package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarketRoutesTest {
    @TempDir lateinit var dir: Path
    private val venue = FakeAdapter()
    private val http = OkHttpClient()
    private val minute = 60_000L
    private var now = 10 * minute + 30_000
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""

    private fun start() {
        gateway = Gateway(venue, Journal.open(dir.resolve("j.db")), mapOf(Role.TRADER to "t")) { now }
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

    private fun quotes(query: String): LinkedBlockingQueue<String> {
        val messages = LinkedBlockingQueue<String>()
        http.newWebSocket(
            Request
                .Builder()
                .url(
                    base.replace("http", "ws") + "/v1/quotes?$query",
                ).header("Authorization", "Bearer t")
                .build(),
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    messages += text
                }
            },
        )
        return messages
    }

    @Test
    fun `a root subscription gets its options' quotes, refreshed with a later time while the link is up`() {
        start()
        val received = quotes("roots=BTC_USDC")
        val deadline = System.currentTimeMillis() + 5_000
        while (venue.subscriptions.none { it.second == setOf("BTC_USDC") }) {
            check(System.currentTimeMillis() < deadline) { "never subscribed" }
            Thread.sleep(10)
        }
        val option = "BTC_USDC-9OCT26-82000-P"

        venue.listener!!.quote(VenueQuote(option, BigDecimal("640"), BigDecimal("655"), timeMs = now))
        venue.listener!!.quote(
            VenueQuote("BTC_USDC-PERPETUAL", BigDecimal("84000"), BigDecimal("84000.5"), timeMs = now),
        )
        assertThat(awaitMessage(received) { it.contains("\"time\":$now") }).contains(option)
        now += 5_000

        assertThat(awaitMessage(received) { it.contains("\"time\":$now") }).contains(option)
        assertThat(seen.none { it.contains("PERPETUAL") }).isTrue()
    }

    @Test
    fun `bars are closed ones only, and a window that does not divide a day is refused`() {
        start()
        venue.bars +=
            (8L..10L).map {
                VenueBar(
                    it * minute,
                    BigDecimal("1"),
                    BigDecimal("2"),
                    BigDecimal("0.5"),
                    BigDecimal("1.5"),
                    BigDecimal("3"),
                )
            }

        val ok = get("/v1/bars?symbol=BTC_USDC-PERPETUAL&window_ms=60000&from=0&to=${20 * minute}")
        val bad = get("/v1/bars?symbol=BTC_USDC-PERPETUAL&window_ms=420000&from=0&to=1")

        assertThat(
            ok,
        ).contains("\"start\":${8 * minute}", "\"start\":${9 * minute}").doesNotContain("\"start\":${10 * minute}")
        assertThat(ok).doesNotContain("\"next\":")
        assertThat(bad).contains("invalid_request")
    }

    private val seen = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun awaitMessage(
        queue: LinkedBlockingQueue<String>,
        match: (String) -> Boolean,
    ): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val message = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            seen += message
            if (match(message)) return message
        }
        error("no matching quote; saw $seen")
    }

    private fun get(path: String) =
        http
            .newCall(
                Request
                    .Builder()
                    .url(base + path)
                    .header("Authorization", "Bearer t")
                    .build(),
            ).execute()
            .use {
                it.body!!.string()
            }
}
