package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RoutesArmsTest {
    @TempDir lateinit var dir: Path

    /** A venue whose klines fail as [klineFailure] says, once set. */
    private class FailingVenue : FakeAdapter() {
        @Volatile var klineFailure: RuntimeException? = null

        override fun klines(
            code: String,
            resolutionMs: Long,
            fromMs: Long,
            toMs: Long,
        ): List<VenueBar> = klineFailure?.let { throw it } ?: super.klines(code, resolutionMs, fromMs, toMs)
    }

    private val venue = FailingVenue()
    private val http = OkHttpClient()
    private val now = 10_000 * 60_000L
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""

    @BeforeEach
    fun start() {
        gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { now },
                mapOf(Role.TRADER to "t", Role.GUARDIAN to "g"),
            ) { now }
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

    private fun call(
        method: String,
        path: String,
        token: String = "g",
        body: String? = null,
    ): Pair<Int, String> =
        http
            .newCall(
                Request
                    .Builder()
                    .url(base + path)
                    .method(method, body?.toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $token")
                    .build(),
            ).execute()
            .use { it.code to it.body.string() }

    private fun socket(path: String): LinkedBlockingQueue<String> {
        val messages = LinkedBlockingQueue<String>()
        http.newWebSocket(
            Request
                .Builder()
                .url(base.replace("http", "ws") + path)
                .header("Authorization", "Bearer t")
                .build(),
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

    private val symbol = "symbol=BTC_USDC-PERPETUAL"

    @Test
    fun `bars, trades and liquidations name the bound they are missing`() {
        assertThat(call("GET", "/v1/bars?$symbol&window_ms=60000&to=10").second).contains("from missing")
        assertThat(call("GET", "/v1/bars?$symbol&window_ms=60000&from=10").second).contains("to missing")
        assertThat(call("GET", "/v1/trades?$symbol&to=10").second).contains("from must be epoch milliseconds")
        assertThat(call("GET", "/v1/trades?$symbol&from=10").second).contains("to must be epoch milliseconds")
        assertThat(call("GET", "/v1/liquidations?$symbol&to=10").second).contains("from must be epoch milliseconds")
    }

    @Test
    fun `a range that starts where it ends has no bars, trades or liquidations and asks the venue for none`() {
        val bars = call("GET", "/v1/bars?$symbol&window_ms=60000&from=60000&to=60000")
        val trades = call("GET", "/v1/trades?$symbol&from=500&to=500")
        val liquidations = call("GET", "/v1/liquidations?$symbol&from=500&to=500")

        assertThat(bars.second).contains("\"bars\":[]")
        assertThat(trades.second).contains("\"trades\":[]")
        assertThat(liquidations.second).contains("\"liquidations\":[]")
        assertThat(venue.barCalls).isEmpty()
    }

    @Test
    fun `a venue that refuses or cannot answer a read is a 422 or a 503, with its words`() {
        venue.klineFailure = VenueRefusedException("no such window")
        val refused = call("GET", "/v1/bars?$symbol&window_ms=60000&from=0&to=600000")
        venue.klineFailure = VenueUnavailableException("venue timed out")
        val unavailable = call("GET", "/v1/bars?$symbol&window_ms=60000&from=0&to=600000")

        assertThat(refused.first).isEqualTo(422)
        assertThat(refused.second).contains("no such window")
        assertThat(unavailable.first).isEqualTo(503)
        assertThat(unavailable.second).contains("venue timed out")
    }

    @Test
    fun `a request the wrong role makes, or sends a body that is not the wire's, is refused`() {
        assertThat(call("GET", "/v1/bars?$symbol", token = "nobody").first).isEqualTo(401)
        assertThat(call("POST", "/v1/orders", token = "t", body = "{not json").first).isEqualTo(400)
        assertThat(call("POST", "/v1/orders", token = "t", body = """{"client_order_id":1}""").second)
            .contains("invalid_request")
    }

    @Test
    fun `the kill switch is set by scope, and says what it is missing`() {
        val notObject = call("POST", "/v1/kill", body = "[1]")
        val noScope = call("POST", "/v1/kill", body = "{}")
        val noSymbols = call("POST", "/v1/kill", body = """{"scope":"symbols"}""")
        val engaged = call("POST", "/v1/kill", body = """{"scope":"symbols","symbols":["A","B"]}""")
        val again = call("POST", "/v1/kill", body = """{"scope":"symbols","symbols":["B","C"]}""")
        val released = call("POST", "/v1/kill/release", body = """{"scope":"symbols","symbols":["A"]}""")

        assertThat(notObject.second).contains("body is not an object")
        assertThat(noScope.second).contains("scope must be all or symbols")
        assertThat(noSymbols.second).contains("symbols missing")
        assertThat(engaged.second).contains("\"A\"", "\"B\"")
        assertThat(again.second).contains("\"A\"", "\"B\"", "\"C\"")
        assertThat(released.second).contains("\"B\"", "\"C\"").doesNotContain("\"A\"")
    }

    @Test
    fun `deals and settlements are listed by order or symbol, or by a range ending now when it names no end`() {
        assertThat(call("GET", "/v1/deals?client_order_id=none").second).contains("\"deals\":[]")
        assertThat(call("GET", "/v1/deals?from=0").second).contains("\"deals\":[]")
        assertThat(call("GET", "/v1/deals").second).contains("from must be epoch milliseconds")
        assertThat(call("GET", "/v1/settlements?$symbol").second).contains("\"settlements\":[]")
        assertThat(call("GET", "/v1/settlements?from=0&to=5").second).contains("\"settlements\":[]")
        assertThat(call("GET", "/v1/settlements").second).contains("from must be epoch milliseconds")
    }

    @Test
    fun `a quote socket of blank symbols, or of roots alone, stays open and quiet`() {
        val blanks = socket("/v1/quotes?symbols=,")
        val roots = socket("/v1/quotes?roots=BTC_USDC&refresh=changes")
        val none = socket("/v1/quotes")

        assertThat(blanks.poll(400, TimeUnit.MILLISECONDS)).isNull()
        assertThat(roots.poll(400, TimeUnit.MILLISECONDS)).isNull()
        assertThat(none.poll(400, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `a stream asked for another stream's position, or beyond the journal, is told to reset`() {
        val journalStream = gateway.journal.stream
        val foreign = socket("/v1/stream?stream=other&since=0")
        val beyond = socket("/v1/stream?stream=$journalStream&since=999")
        val live = socket("/v1/stream?stream=$journalStream")
        val foreignNoSince = socket("/v1/stream?stream=other")

        assertThat(foreign.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"reset\"")
        assertThat(beyond.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"reset\"")
        assertThat(live.poll(400, TimeUnit.MILLISECONDS)).describedAs("the same stream, no position").isNull()
        assertThat(foreignNoSince.poll(400, TimeUnit.MILLISECONDS)).describedAs("no position to reset").isNull()
    }
}
