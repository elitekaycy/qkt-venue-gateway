package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.Fixtures
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitPublicClientTest {
    private val server =
        Fixtures.Server { path, query ->
            when (path) {
                "/v5/market/instruments-info" ->
                    when (query["cursor"]) {
                        null -> "instruments-linear-page-1.json"
                        "first=0GUSDT&last=1000000BABYDOGEUSDT" -> "instruments-linear-future.json"
                        else -> null
                    }
                "/v5/market/tickers" -> "ticker-linear-BTCUSDT.json"
                "/v5/market/kline" -> "kline-linear-1m-15.json"
                "/v5/market/funding/history" -> "funding-history.json"
                "/v5/market/open-interest" -> "open-interest-5min.json"
                "/v5/market/recent-trade" -> "recent-trade-linear.json"
                "/v5/market/orderbook" -> "orderbook-linear.json"
                else -> null
            }
        }
    private val market = BybitPublicClient(BybitRest(server.url))

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `the listing follows bybit's page cursor to the last page`() {
        val listed = market.instruments("linear")

        assertThat(listed.map { it.symbol }).containsExactly("0GUSDT", "1000000BABYDOGEUSDT", "BTCUSDT-30OCT26")
        val future = listed.last()
        assertThat(future.contractType).isEqualTo("LinearFutures")
        assertThat(future.deliveryMs).isEqualTo(1_793_347_200_000L)
        assertThat(listed.first().deliveryMs).isNull()
        assertThat(server.requests.map { it.requestUrl!!.queryParameter("category") }).containsOnly("linear")
    }

    @Test
    fun `a ticker carries its sides, mark, index and open interest at bybit's time`() {
        val ticker = market.ticker("linear", "BTCUSDT")!!

        assertThat(ticker.timeMs).isEqualTo(1_791_239_689_155L)
        assertThat(ticker.bid).isEqualByComparingTo("87717.60")
        assertThat(ticker.bidSize).isEqualByComparingTo("0.006")
        assertThat(ticker.ask).isEqualByComparingTo("87717.90")
        assertThat(ticker.mark).isEqualByComparingTo("87717.60")
        assertThat(ticker.index).isEqualByComparingTo("85971.37")
        assertThat(ticker.openInterest).isEqualByComparingTo("253961.849")
    }

    @Test
    fun `klines come back oldest first, each once, decimals exact`() {
        val klines = market.klines("linear", "BTCUSDT", "kline", "1", 60_000, 1_791_240_180_000, 1_791_241_079_999)

        assertThat(klines).hasSize(15)
        assertThat(klines.map { it.startMs }).isSorted.doesNotHaveDuplicates()
        assertThat(klines.first().startMs).isEqualTo(1_791_240_180_000L)
        assertThat(klines.all { it.volume != null }).isTrue
        val sent = server.requests.single().requestUrl!!
        assertThat(sent.queryParameter("start")).isEqualTo("1791240180000")
        assertThat(sent.queryParameter("end")).isEqualTo("1791241079999")
        assertThat(sent.queryParameter("limit")).isEqualTo("1000")
    }

    @Test
    fun `funding rates in range come back oldest first`() {
        val rates = market.fundingRates("linear", "BTCUSDT", 1_791_100_800_000, 1_791_239_000_000)

        assertThat(rates.map { it.timeMs }).containsExactly(
            1_791_100_800_000L,
            1_791_129_600_000L,
            1_791_158_400_000L,
            1_791_187_200_000L,
            1_791_216_000_000L,
        )
        assertThat(rates[1].rate).isEqualTo(BigDecimal("0.00072477"))
    }

    @Test
    fun `open interest is read back to the start of the range, oldest first, at bybit's stamps`() {
        val figures = market.openInterest("linear", "BTCUSDT", "5min", 1_791_238_200_000, 1_791_239_500_000)

        assertThat(figures.map { it.timeMs }).containsExactly(
            1_791_238_200_000L,
            1_791_238_500_000L,
            1_791_238_800_000L,
            1_791_239_100_000L,
            1_791_239_400_000L,
        )
        assertThat(figures.last().openInterest).isEqualByComparingTo("253961.847")
    }

    @Test
    fun `the latest trades come back oldest first with the taker's side`() {
        val trades = market.recentTrades("linear", "BTCUSDT", 5)

        assertThat(trades.map { it.timeMs }).isSorted
        assertThat(trades.last().id).isEqualTo("9c2c7783-e233-5bbb-8f8c-36154b2e27ac")
        assertThat(trades.last().side).isEqualTo("Sell")
        assertThat(trades.last().size).isEqualByComparingTo("0.004")
    }

    @Test
    fun `the book is read best first at bybit's own stamp`() {
        val book = market.orderBook("linear", "BTCUSDT", 10)

        assertThat(book.timeMs).isEqualTo(1_791_239_695_194L)
        assertThat(book.bids.first()).isEqualTo(BigDecimal("87717.30") to BigDecimal("0.003"))
        assertThat(book.asks.first()).isEqualTo(BigDecimal("87717.80") to BigDecimal("0.009"))
        assertThat(book.bids).hasSize(10)
    }
}
