package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitOpenInterestArmsTest {
    private var history = "open-interest-5min.json"
    private var ticker: String? = "ticker-linear-BTCUSDT.json"
    private val server =
        Fixtures.Server { path, _ ->
            when (path) {
                "/v5/market/open-interest" -> history
                "/v5/market/tickers" -> ticker
                else -> null
            }
        }
    private val market = BybitPublicClient(BybitRest(server.url))
    private val now = 1_791_239_699_000L

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `a ticker with no open interest adds no present figure`() {
        ticker = "ticker-spot-BTCUSDT.json"

        val figures = BybitOpenInterest(market, "linear") { now }.read("BTCUSDT", 1_791_238_000_000, now)

        assertThat(figures.map { it.timeMs }).containsExactly(
            1_791_238_500_000L,
            1_791_238_800_000L,
            1_791_239_100_000L,
            1_791_239_400_000L,
        )
    }

    @Test
    fun `a ticker bybit has no row for adds no present figure`() {
        ticker = "ticker-linear-unlisted.json"

        val figures = BybitOpenInterest(market, "linear") { now }.read("BTCUSDT", 1_791_238_000_000, now)

        assertThat(figures).hasSize(4)
    }

    @Test
    fun `an empty history ends with the present figure alone`() {
        history = "open-interest-empty.json"

        val figures = BybitOpenInterest(market, "linear") { now }.read("BTCUSDT", 1_791_238_000_000, now)

        assertThat(figures.map { it.timeMs }).containsExactly(1_791_239_689_155L)
    }

    @Test
    fun `figures published before the range starts are not served`() {
        val figures = BybitOpenInterest(market, "linear") { now }.read("BTCUSDT", 1_791_238_700_000, 1_791_239_000_000)

        assertThat(figures.map { it.timeMs }).containsExactly(1_791_238_800_000L)
    }
}
