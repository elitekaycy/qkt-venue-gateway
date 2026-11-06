package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.bybit.client.BybitMarketJson
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BybitAdapterMarketTest {
    @TempDir lateinit var dir: Path
    private val venues = mutableListOf<BybitVenue>()

    /** Bybit's recorded ticker time. */
    private val tickerTime = 1_791_239_695_000L

    private fun venue(now: Long) = BybitVenue(dir, now = now).also { venues += it }

    @AfterEach
    fun stop() = venues.forEach { it.close() }

    @Test
    fun `funding rates are bybit's settled rates in the range, oldest first`() {
        val rates = venue(tickerTime).adapter.fundingRates("BTCUSDT", 1_791_100_800_000, 1_791_216_000_000)

        assertThat(rates).hasSize(5)
        assertThat(rates.first().timeMs).isEqualTo(1_791_100_800_000L)
        assertThat(rates.last().timeMs).isEqualTo(1_791_216_000_000L)
        assertThat(rates.last().rate).isEqualByComparingTo("0.0001")
    }

    @Test
    fun `open interest is served at the end of each five minutes`() {
        val figures = venue(tickerTime).adapter.openInterest("BTCUSDT", 1_791_238_000_000, 1_791_239_000_000)

        assertThat(figures.map { it.timeMs }).containsExactly(1_791_238_500_000L, 1_791_238_800_000L)
    }

    @Test
    fun `marks are each closed minute's mark and index`() {
        val to = 1_791_241_080_000L

        val marks = venue(to).adapter.marks("BTCUSDT", 60_000, 1_791_240_180_000, to)

        assertThat(marks).hasSize(15)
        assertThat(marks.all { it.mark != null && it.index != null }).isTrue
    }

    @Test
    fun `klines are bybit's minute klines over the range`() {
        val to = 1_791_241_080_000L
        val venue = venue(to)

        val bars = venue.adapter.klines("BTCUSDT", 60_000, 1_791_240_180_000, to)

        assertThat(bars).hasSize(15)
        assertThat(bars.last().volume).isEqualByComparingTo("0.048")
        assertThat(venue.param("/v5/market/kline", "interval")).isEqualTo("1")
    }

    @Test
    fun `the tape is bybit's latest trades plus every trade the socket pushed, and reading it tapes the code`() {
        val venue = venue(tickerTime)
        val pushed = BybitVenue.items("ws-linear-public-trade.json").map(BybitMarketJson::streamTrade)
        venue.onTrades("BTCUSDT", pushed)

        val prints = venue.adapter.trades("BTCUSDT", 1_791_239_680_000, 1_791_240_200_000, 100)

        assertThat(prints).hasSize(6)
        assertThat(prints.last().id).isEqualTo("c9973f17-3657-54b1-b419-3690be7a964b")
        assertThat(prints.last().side).isEqualTo(Side.SELL)
        assertThat(venue.param("/v5/market/recent-trade", "limit")).isEqualTo("1000")
        assertThat(Files.readString(dir.resolve("taped.txt"))).contains("BTCUSDT")
    }

    @Test
    fun `liquidations pushed are served with the side of the order that closed the position`() {
        val venue = venue(tickerTime)
        val pushed = BybitVenue.items("ws-linear-all-liquidation.json").map(BybitMarketJson::liquidation)
        venue.onLiquidations("BTCUSDT", pushed)

        val served = venue.adapter.liquidations("BTCUSDT", 1_791_241_484_000, 1_791_241_485_000)

        assertThat(served.map { it.size.toPlainString() }).containsExactlyInAnyOrder("0.015", "0.130")
        assertThat(served.map { it.side }).containsOnly(Side.BUY)
    }

    @Test
    fun `depth reaching now records bybit's book of ten levels and serves it`() {
        val venue = venue(tickerTime)

        val depth = venue.adapter.depth("BTCUSDT", 1_791_239_000_000, tickerTime + 1_000)

        val book = depth.single()
        assertThat(book.timeMs).isEqualTo(1_791_239_695_194L)
        assertThat(book.asks.first().price).isEqualByComparingTo("87717.80")
        assertThat(venue.param("/v5/market/orderbook", "limit")).isEqualTo("10")
    }
}
