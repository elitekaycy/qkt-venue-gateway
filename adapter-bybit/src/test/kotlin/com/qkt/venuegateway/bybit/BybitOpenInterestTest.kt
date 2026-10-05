package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitOpenInterestTest {
    private val server =
        Fixtures.Server { path, _ ->
            when (path) {
                "/v5/market/open-interest" -> "open-interest-5min.json"
                "/v5/market/tickers" -> "ticker-linear-BTCUSDT.json"
                else -> null
            }
        }
    private val market = BybitPublicClient(BybitRest(server.url))

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `each figure is served at the end of its five minutes, never before bybit published it`() {
        val reader = BybitOpenInterest(market, "linear") { 1_791_239_695_000L }

        val figures = reader.read("BTCUSDT", 1_791_238_000_000, 1_791_239_000_000)

        assertThat(figures.map { it.timeMs }).containsExactly(1_791_238_500_000L, 1_791_238_800_000L)
        assertThat(figures.first().openInterest).isEqualByComparingTo("253961.657")
        assertThat(server.requests.map { it.requestUrl!!.encodedPath }).doesNotContain("/v5/market/tickers")
    }

    @Test
    fun `a window reaching now ends with the ticker's present figure at bybit's time`() {
        val reader = BybitOpenInterest(market, "linear") { 1_791_239_699_000L }

        val figures = reader.read("BTCUSDT", 1_791_238_000_000, 1_791_239_699_000)

        assertThat(figures.map { it.timeMs }).containsExactly(
            1_791_238_500_000L,
            1_791_238_800_000L,
            1_791_239_100_000L,
            1_791_239_400_000L,
            1_791_239_689_155L,
        )
        assertThat(figures.last().openInterest).isEqualByComparingTo("253961.849")
        assertThat(figures.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs }).isTrue
    }
}
