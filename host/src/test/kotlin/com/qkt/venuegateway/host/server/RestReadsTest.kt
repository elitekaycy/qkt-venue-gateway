package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.vgp.WireFill
import com.qkt.vgp.WireSettlement
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

class RestReadsTest {
    /** A venue whose account read can carry margins, be refused or be unreachable. */
    private class AccountVenue : FakeAdapter() {
        @Volatile var reads: (() -> AccountSnapshot)? = null

        override fun account() = reads?.invoke() ?: super.account()
    }

    @TempDir lateinit var dir: Path
    private val venue = AccountVenue()
    private val http = OkHttpClient()
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
                InstrumentShelf.open(dir.resolve("i.db")) { 1_000L },
                mapOf(Role.TRADER to "t", Role.GUARDIAN to "g"),
            ) { 1_000L }
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
        authorization: String = "Bearer t",
        body: String? = null,
    ): Pair<Int, String> {
        val request =
            Request
                .Builder()
                .url(base + path)
                .method(method, body?.toRequestBody("application/json".toMediaType()))
                .header("Authorization", authorization)
                .build()
        return http.newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun kill(
        path: String,
        body: String,
    ) = call("POST", path, "Bearer g", body)

    @Test
    fun `the account is served in decimal strings, its margins left out unless the venue reports them`() {
        val bare = call("GET", "/v1/account")
        val snapshot = AccountSnapshot("BTC", BigDecimal("1.50"), BigDecimal("2"), BigDecimal("0.1"), BigDecimal("1.9"))
        venue.reads = { snapshot.copy(initialMargin = BigDecimal("0.2"), maintenanceMargin = BigDecimal("0.05")) }
        val margined = call("GET", "/v1/account")

        assertThat(bare.first).isEqualTo(200)
        assertThat(bare.second).isEqualTo(
            """{"currency":"USDC","balance":"10000","equity":"10000",""" +
                """"margin_used":"0","margin_available":"10000"}""",
        )
        assertThat(margined.second)
            .contains("\"balance\":\"1.50\"", "\"initial_margin\":\"0.2\"", "\"maintenance_margin\":\"0.05\"")
    }

    @Test
    fun `a venue refusing a read is 422 with its reason, and a venue out of reach is 503`() {
        venue.reads = { throw VenueRefusedException("account locked") }
        val refused = call("GET", "/v1/account")
        venue.reads = { throw VenueUnavailableException("venue down") }
        val down = call("GET", "/v1/account")

        assertThat(refused.first).isEqualTo(422)
        assertThat(refused.second).contains("\"code\":\"venue_rejected\"", "\"message\":\"account locked\"")
        assertThat(down.first).isEqualTo(503)
        assertThat(down.second).contains("\"code\":\"venue_unavailable\"", "\"message\":\"venue down\"")
    }

    @Test
    fun `instruments list every contract the venue lists, and positions are signed per symbol`() {
        venue.net["BTC_USDC-PERPETUAL"] = BigDecimal("-0.2")

        val (listed, instruments) = call("GET", "/v1/instruments")
        val (held, positions) = call("GET", "/v1/positions")

        assertThat(listed).isEqualTo(200)
        assertThat(instruments).contains("\"code\":\"BTC_USDC-PERPETUAL\"", "\"code\":\"BTC_USDC-9OCT26-82000-P\"")
        assertThat(held).isEqualTo(200)
        assertThat(positions).isEqualTo(
            """{"accounting":"netting","positions":""" +
                """[{"symbol":"BTC_USDC-PERPETUAL","quantity":"-0.2","avg_price":"84000"}]}""",
        )
    }

    @Test
    fun `working orders list only the orders this gateway placed`() {
        val submit =
            """{"client_order_id":"mine","symbol":"BTC_USDC-PERPETUAL","side":"buy","type":"limit",""" +
                """"quantity":"0.1","limit_price":"50000","time_in_force":"gtc"}"""
        call("POST", "/v1/orders", body = submit)
        venue.orders["theirs"] = venue.orders.getValue("mine").copy(clientOrderId = "theirs")

        val (status, body) = call("GET", "/v1/orders")

        assertThat(status).isEqualTo(200)
        assertThat(body).contains("\"client_order_id\":\"mine\"").doesNotContain("theirs")
    }

    @Test
    fun `deals are served for one order and settlements for one symbol`() {
        gateway.journal.appendFill(WireFill("a-1", null, "f-1", "BTC_USDC-PERPETUAL", "buy", "0.1", "84000", 900L))
        gateway.journal.appendFill(WireFill("a-2", null, "f-2", "BTC_USDC-PERPETUAL", "buy", "0.1", "84000", 900L))
        gateway.journal.appendSettlement(WireSettlement("BTC-27SEP26", "84000", 900L))
        gateway.journal.appendSettlement(WireSettlement("BTC-25DEC26", "90000", 900L))

        val deals = call("GET", "/v1/deals?client_order_id=a-1").second
        val settled = call("GET", "/v1/settlements?symbol=BTC-25DEC26").second

        assertThat(deals).contains("\"fill_id\":\"f-1\"").doesNotContain("f-2")
        assertThat(settled).contains("BTC-25DEC26").doesNotContain("BTC-27SEP26")
    }

    @Test
    fun `a kill scope of symbols adds and removes them, and a malformed scope is refused by name`() {
        val engaged = kill("/v1/kill", """{"scope":"symbols","symbols":["A","B"]}""")
        val more = kill("/v1/kill", """{"scope":"symbols","symbols":["B","C"]}""")
        val released = kill("/v1/kill/release", """{"scope":"symbols","symbols":["A"]}""")

        assertThat(engaged.second).isEqualTo("""{"all":false,"symbols":["A","B"]}""")
        assertThat(more.second).isEqualTo("""{"all":false,"symbols":["A","B","C"]}""")
        assertThat(released.second).isEqualTo("""{"all":false,"symbols":["B","C"]}""")
        assertThat(kill("/v1/kill", "[1]")).isEqualTo(400 to error("body is not an object"))
        assertThat(kill("/v1/kill", """{"scope":"symbols"}""")).isEqualTo(400 to error("symbols missing"))
        assertThat(kill("/v1/kill", """{"scope":"some"}""")).isEqualTo(400 to error("scope must be all or symbols"))
    }

    @Test
    fun `a token not sent as a bearer token is no token`() {
        val (status, body) = call("GET", "/v1/health", authorization = "Basic t")

        assertThat(status).isEqualTo(401)
        assertThat(body).contains("\"code\":\"unauthorized\"")
    }

    private fun error(message: String) = """{"error":{"code":"invalid_request","message":"$message"}}"""
}
