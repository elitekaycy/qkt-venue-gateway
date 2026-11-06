package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.ListedVenue
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.venuegateway.host.market.perpetual
import java.nio.file.Path
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentRoutesTest {
    @TempDir lateinit var dir: Path
    private val venue = ListedVenue()
    private val http = OkHttpClient()
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""

    private val perp =
        """{"code":"BTC_USDC-PERPETUAL","kind":"perpetual","currency":"USDC","contract_size":"1",""" +
            """"tick_size":"0.5","volume_step":"0.001","volume_min":"0.001"}"""
    private val option =
        """{"code":"BTC_USDC-9OCT26-82000-P","kind":"option","currency":"USDC","contract_size":"1",""" +
            """"tick_size":"5","volume_step":"0.01","volume_min":"0.01","expiry":1791532800000,"strike":"82000",""" +
            """"right":"put","underlying":"BTC_USDC"}"""

    @BeforeEach
    fun start() {
        gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { 1_000L },
                mapOf(Role.TRADER to "t"),
            ) { 1_000L }
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

    private fun get(path: String): Pair<Int, String> =
        http
            .newCall(
                Request
                    .Builder()
                    .url(base + path)
                    .header("Authorization", "Bearer t")
                    .build(),
            ).execute()
            .use { it.code to it.body.string() }

    @Test
    fun `with no catalog parameter the listing reads byte for byte as before, whatever else the query holds`() {
        assertThat(get("/v1/instruments")).isEqualTo(200 to "[$perp,$option]")
        assertThat(get("/v1/instruments?symbols=x")).isEqualTo(200 to "[$perp,$option]")
    }

    @Test
    fun `a filtered listing is a page with the time the gateway read it`() {
        assertThat(get("/v1/instruments?kind=perpetual").second).isEqualTo("""{"instruments":[$perp],"as_of":1000}""")
        assertThat(get("/v1/instruments?limit=1").second)
            .isEqualTo("""{"instruments":[$option],"next":"BTC_USDC-9OCT26-82000-P","as_of":1000}""")
        assertThat(get("/v1/instruments?updated_since=1000&kind=option").second)
            .isEqualTo("""{"instruments":[$option],"removed":[],"as_of":1000}""")
    }

    @Test
    fun `a bad catalog parameter is a bad request naming it`() {
        val (status, body) = get("/v1/instruments?limit=9999")

        assertThat(status).isEqualTo(400)
        assertThat(body).contains("invalid_request", "limit")
    }

    @Test
    fun `a code the venue no longer lists is served as the adapter answers for it`() {
        venue.unlisted += perpetual("OLD_USDC-PERPETUAL")

        assertThat(get("/v1/instruments/OLD_USDC-PERPETUAL").second).contains("\"code\":\"OLD_USDC-PERPETUAL\"")
        assertThat(get("/v1/instruments/BTC_USDC-PERPETUAL").second).isEqualTo(perp)
    }
}
