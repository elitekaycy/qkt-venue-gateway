package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.kalshi.client.KalshiMarketJson
import com.qkt.venuegateway.kalshi.client.KalshiPortfolioJson
import com.qkt.venuegateway.kalshi.client.KalshiSettlement
import java.math.BigDecimal
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KalshiMarketMappingTest {
    private fun msg(name: String) = Fixtures.json("socket/$name.json")["msg"]!!.jsonObject

    private fun list(
        name: String,
        key: String,
    ) = Fixtures.json(name)[key]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `a ticker is a yes quote; Kalshi's 0 bid and 1 ask are absent sides`() {
        val both = KalshiMarketMapping.quote(KalshiMarketJson.ticker(msg("ticker")))
        val noAsk = KalshiMarketMapping.quote(KalshiMarketJson.ticker(msg("ticker-no-ask")))
        val noBid = KalshiMarketMapping.quote(KalshiMarketJson.ticker(msg("ticker-no-bid")))

        assertThat(both.bid).isEqualByComparingTo("0.02")
        assertThat(both.ask).isEqualByComparingTo("0.04")
        assertThat(both.bidSize).isEqualByComparingTo("1.5")
        assertThat(both.askSize).isEqualByComparingTo("10")
        assertThat(both.timeMs).isEqualTo(1_793_927_034_085L)
        assertThat(noAsk.ask).isNull()
        assertThat(noAsk.askSize).isNull()
        assertThat(noAsk.bid).isEqualByComparingTo("0.02")
        assertThat(noBid.bid).isNull()
        assertThat(noBid.bidSize).isNull()
    }

    @Test
    fun `a listed market's quote reads the same way`() {
        val recorded = Fixtures.json("demo/market-KXNBA3D-26NOV05MIAGSW-GSWSCURRY30.json")
        val m = KalshiMarketJson.market(recorded["market"]!!.jsonObject)
        val q = KalshiMarketMapping.quote(m, 9L)

        assertThat(q.symbol).isEqualTo("KXNBA3D-26NOV05MIAGSW-GSWSCURRY30")
        assertThat(q.timeMs).isEqualTo(9L)
        assertThat(q.bid).isNull()
        assertThat(q.ask).isEqualByComparingTo("0.04")
    }

    @Test
    fun `a book's no bids are yes asks at one less their price, best first, an empty side empty`() {
        val book = KalshiMarketJson.book(Fixtures.json("public/orderbook-KXHIGHNY-26NOV06-B67.5.json"))
        val depth = KalshiMarketMapping.depth(book, 5L)
        val tail = KalshiMarketJson.book(Fixtures.json("public/orderbook-KXHIGHNY-26NOV06-T74.json"))
        val oneSided = KalshiMarketMapping.depth(tail, 5L)

        assertThat(depth.bids.map { it.price }).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(depth.asks.map { it.price }).isSorted
        assertThat(depth.bids.first().price).isLessThan(depth.asks.first().price)
        assertThat(depth.bids.size).isLessThanOrEqualTo(10)
        assertThat(oneSided.bids).isEmpty()
        assertThat(oneSided.asks.first().price).isEqualByComparingTo("0.01")
        assertThat(oneSided.asks.first().amount).isEqualByComparingTo("1837.99")
    }

    @Test
    fun `a candle is a bar of the yes mid starting a period before its end, and none when a side was empty`() {
        val candles =
            list("public/candles-1m-KXHIGHNY-26NOV06-B67.5.json", "candlesticks").map(KalshiMarketJson::candle)
        val bar = KalshiMarketMapping.bar(candles.minBy { it.endMs }, 60_000L)!!
        val empty = list("public/candles-1m-KXHIGHNY-26NOV06-T74.json", "candlesticks").map(KalshiMarketJson::candle)

        assertThat(bar.startMs).isEqualTo(1_793_925_060_000L)
        assertThat(bar.open).isEqualByComparingTo("0.525")
        assertThat(bar.high).isEqualByComparingTo("0.525")
        assertThat(bar.low).isEqualByComparingTo("0.5")
        assertThat(bar.close).isEqualByComparingTo("0.505")
        assertThat(bar.volume).isEqualByComparingTo("139.35")
        assertThat(KalshiMarketMapping.bar(empty.single(), 60_000L)).isNull()
    }

    @Test
    fun `a trade's side is its taker's`() {
        val trades = list("public/trades-page-1.json", "trades").map(KalshiMarketJson::trade)
        val prints = trades.map(KalshiMarketMapping::print)

        assertThat(prints.first().side).isEqualTo(Side.SELL)
        assertThat(prints.first().price).isEqualByComparingTo("0.49")
        assertThat(prints.first().size).isEqualByComparingTo("5.73")
        assertThat(prints.map { it.side }).contains(Side.BUY)
        assertThatThrownBy { KalshiMarketMapping.side("middle") }.hasMessageContaining("book side middle")
    }

    @Test
    fun `a fill is the yes price and count, its fee an exchange fee in usd`() {
        val f = KalshiMarketMapping.fill(KalshiPortfolioJson.fill(msg("fill")), "qkt-probe-fill")
        val rest = list("private/fills.json", "fills").map(KalshiPortfolioJson::fill)

        assertThat(f.fillId).isEqualTo("072717e6-50f1-a0a3-ea98-bda3c7188c22")
        assertThat(f.venueOrderId).isEqualTo("01a1ae61-f228-76f6-9226-9c3a49437fb5")
        assertThat(f.side).isEqualTo(Side.BUY)
        assertThat(f.quantity).isEqualByComparingTo("2")
        assertThat(f.price).isEqualByComparingTo("0.04")
        assertThat(f.timeMs).isEqualTo(1_793_927_017_046L)
        assertThat(f.costs.single().kind).isEqualTo(CostKind.EXCHANGE_FEE)
        assertThat(f.costs.single().amount).isEqualByComparingTo("0.0054")
        assertThat(rest.first().timeMs).isEqualTo(1_793_927_092_759L)
        assertThat(KalshiMarketMapping.fill(rest.first().copy(fee = BigDecimal.ZERO), "x").costs).isEmpty()
    }

    @Test
    fun `a settlement pays 1, 0 or a scalar's value at Kalshi's settled time, its fee a delivery fee`() {
        fun s(
            result: String,
            cents: Int? = null,
            fee: String = "0",
        ) = KalshiMarketMapping.settlement(KalshiSettlement("T", result, cents, 7L, BigDecimal(fee)))

        assertThat(s("yes")!!.price).isEqualByComparingTo("1")
        assertThat(s("no")!!.price).isEqualByComparingTo("0")
        assertThat(s("scalar", 37)!!.price).isEqualByComparingTo("0.37")
        assertThat(s("yes", fee = "0.01")!!.costs.single().kind).isEqualTo(CostKind.DELIVERY_FEE)
        assertThat(s("yes")!!.timeMs).isEqualTo(7L)
        assertThat(s("")).isNull()
        assertThatThrownBy { s("scalar") }.hasMessageContaining("without a value")
        assertThatThrownBy { s("void") }.hasMessageContaining("market_result void")
    }

    @Test
    fun `a position is signed yes contracts at the yes price paid`() {
        val rows = list("private/positions.json", "market_positions").map(KalshiPortfolioJson::position)
        val byCode = rows.mapNotNull(KalshiMarketMapping::position).associateBy { it.symbol }

        assertThat(byCode.getValue("KXNBA3D-26NOV05MIAGSW-GSWSCURRY30").avgPrice).isEqualByComparingTo("0.04")
        assertThat(byCode.getValue("KXAAAGASD-26NOV06-4.0600").quantity).isEqualByComparingTo("-1")
        assertThat(byCode.getValue("KXAAAGASD-26NOV06-4.0600").avgPrice).isEqualByComparingTo("0.1")
        assertThat(KalshiMarketMapping.position(rows.first().copy(position = BigDecimal.ZERO))).isNull()
    }
}
