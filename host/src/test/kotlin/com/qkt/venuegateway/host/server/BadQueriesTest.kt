package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import java.nio.file.Path
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BadQueriesTest {
    @TempDir lateinit var dir: Path
    private val venue = FakeAdapter()
    private val http = OkHttpClient()
    private val now = 10_000 * 60_000L
    private val q = "symbol=BTC_USDC-PERPETUAL"
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
                    .header("Authorization", "Bearer g")
                    .build(),
            ).execute()
            .use { it.code to it.body.string() }

    private fun refused(path: String): String {
        val (status, body) = get(path)
        assertThat(status).describedAs(path).isEqualTo(400)
        return body
    }

    @Test
    fun `bars name the parameter they are missing or cannot use`() {
        assertThat(refused("/v1/bars?window_ms=60000&from=0&to=1")).contains("symbol missing")
        assertThat(refused("/v1/bars?$q&from=0&to=1")).contains("window_ms missing")
        assertThat(refused("/v1/bars?$q&window_ms=0&from=0&to=1"))
            .contains("window_ms must be whole minutes dividing a day: 0")
        assertThat(refused("/v1/bars?$q&window_ms=90000&from=0&to=1")).contains("dividing a day: 90000")
        assertThat(refused("/v1/bars?$q&window_ms=420000&from=0&to=1")).contains("dividing a day: 420000")
        assertThat(refused("/v1/bars?$q&window_ms=60000&to=1")).contains("from missing")
        assertThat(refused("/v1/bars?$q&window_ms=60000&from=0")).contains("to missing")
    }

    @Test
    fun `every history names a missing symbol or window bound`() {
        assertThat(refused("/v1/marks?window_ms=60000&from=0&to=1")).contains("symbol missing")
        assertThat(refused("/v1/funding-rates?from=0&to=1")).contains("symbol missing")
        assertThat(refused("/v1/depth?from=0&to=1")).contains("symbol missing")
        assertThat(refused("/v1/open-interest?from=0&to=1")).contains("symbol missing")
        assertThat(refused("/v1/trades?from=0&to=1")).contains("symbol missing")
        assertThat(refused("/v1/funding?to=1")).contains("from must be epoch milliseconds")
        assertThat(refused("/v1/funding-rates?$q&from=0")).contains("to must be epoch milliseconds")
    }
}
