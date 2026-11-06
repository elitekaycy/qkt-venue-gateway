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

/**
 * A window starting at or after the gateway's time holds nothing yet: every history answers an empty page without
 * asking the venue (the fake venue serves no depth, open interest or tape, so asking it would be a `501`).
 */
class FutureWindowsTest {
    @TempDir lateinit var dir: Path
    private val venue = FakeAdapter()
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
    fun `marks and funding rates of a window from now on are empty and never asked of the venue`() {
        val marks = get("/v1/marks?symbol=BTC_USDC-PERPETUAL&window_ms=60000&from=$now&to=${now + 60_000}")
        val rates = get("/v1/funding-rates?symbol=BTC_USDC-PERPETUAL&from=${now + 1}&to=${now + 2}")

        assertThat(marks.first).isEqualTo(200)
        assertThat(marks.second).contains("\"marks\":[]")
        assertThat(rates.first).isEqualTo(200)
        assertThat(rates.second).contains("\"rates\":[]")
        assertThat(venue.markCalls).isEmpty()
        assertThat(venue.rateCalls).isEmpty()
    }

    @Test
    fun `depth and open interest of a window after now are empty pages`() {
        val depth = get("/v1/depth?symbol=BTC_USDC-PERPETUAL&from=${now + 1}&to=${now + 2}")
        val openInterest = get("/v1/open-interest?symbol=BTC_USDC-PERPETUAL&from=${now + 1}&to=${now + 2}")

        assertThat(depth).isEqualTo(200 to """{"depth":[]}""")
        assertThat(openInterest).isEqualTo(200 to """{"open_interest":[]}""")
    }

    @Test
    fun `trades and liquidations of a window from now on are empty pages`() {
        val trades = get("/v1/trades?symbol=BTC_USDC-PERPETUAL&from=$now&to=${now + 5}")
        val liquidations = get("/v1/liquidations?symbol=BTC_USDC-PERPETUAL&from=$now&to=${now + 5}")

        assertThat(trades).isEqualTo(200 to """{"trades":[]}""")
        assertThat(liquidations).isEqualTo(200 to """{"liquidations":[]}""")
    }
}
