package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import java.math.BigDecimal
import java.nio.file.Path
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RoutesMoreArmsTest {
    @TempDir lateinit var dir: Path

    /** A venue that answers its tapes whatever range it is asked, as a sloppy venue may. */
    private class SloppyVenue : FakeAdapter() {
        @Volatile var served: List<VenuePrint> = emptyList()

        override fun trades(
            code: String,
            fromMs: Long,
            toMs: Long,
            limit: Int,
        ) = served

        override fun liquidations(
            code: String,
            fromMs: Long,
            toMs: Long,
        ) = served
    }

    private val venue = SloppyVenue()
    private val http = OkHttpClient()
    private val now = 10_000L
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""
    private val symbol = "symbol=BTC_USDC-PERPETUAL"

    @BeforeEach
    fun start() {
        gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { now },
                mapOf(Role.TRADER to "t"),
            ) { now }
        gateway.start()
        venue.listener!!.connection(true, "test")
        hub = QuoteHub(gateway)
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
        body: String? = null,
    ): Pair<Int, String> =
        http
            .newCall(
                Request
                    .Builder()
                    .url(base + path)
                    .method(method, body?.toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer t")
                    .build(),
            ).execute()
            .use { it.code to it.body.string() }

    private fun print(
        id: String,
        time: Long,
    ) = VenuePrint(id, time, BigDecimal("1"), BigDecimal("1"), Side.BUY)

    @Test
    fun `a bound that is not a number is a bad request, as a missing one is`() {
        assertThat(call("GET", "/v1/bars?$symbol&window_ms=60000&from=abc&to=10").second)
            .contains("from missing")
        assertThat(call("GET", "/v1/bars?$symbol&window_ms=60000&from=10&to=abc").second).contains("to missing")
        assertThat(call("GET", "/v1/bars?$symbol&window_ms=soon&from=10&to=20").second).contains("window_ms missing")
    }

    @Test
    fun `an order is read by its id, not found before it is placed and found after`() {
        val missing = call("GET", "/v1/orders/never-sent")
        val body =
            """{"client_order_id":"a-1","symbol":"BTC_USDC-PERPETUAL","side":"buy","type":"market",""" +
                """"quantity":"0.1",""" +
                """"time_in_force":"gtc","reduce_only":false}"""
        call("POST", "/v1/orders", body)

        val found = call("GET", "/v1/orders/a-1")

        assertThat(missing.first).isEqualTo(404)
        assertThat(found.first).isEqualTo(200)
        assertThat(found.second).contains("\"client_order_id\":\"a-1\"")
    }

    @Test
    fun `trades a venue sends outside the range asked are dropped, oldest first`() {
        venue.served = listOf(print("late", 9_000), print("b", 400), print("early", 50), print("a", 300))

        val page = call("GET", "/v1/trades?$symbol&from=100&to=1000").second

        assertThat(page).contains("\"a\"", "\"b\"").doesNotContain("early", "late")
        assertThat(page.indexOf("\"a\"")).isLessThan(page.indexOf("\"b\""))
    }

    @Test
    fun `a full page of trades in one millisecond cannot be paged, and is a server error`() {
        venue.served = (1..TRADES_PAGE).map { print("t$it", 100) }

        val (status, _) = call("GET", "/v1/trades?$symbol&from=100&to=1000")

        assertThat(status).isEqualTo(500)
    }

    @Test
    fun `liquidations a venue sends outside the range asked are dropped`() {
        venue.served = listOf(print("outside", 90_000), print("inside", 500), print("before", 10))

        val page = call("GET", "/v1/liquidations?$symbol&from=100&to=1000").second

        assertThat(page).contains("inside").doesNotContain("outside", "before")
    }
}
