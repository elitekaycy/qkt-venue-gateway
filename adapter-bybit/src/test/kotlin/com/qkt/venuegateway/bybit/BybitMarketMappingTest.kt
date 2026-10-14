package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.bybit.client.BybitMarketJson
import com.qkt.venuegateway.bybit.client.BybitPrint
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitMarketMappingTest {
    private fun result(name: String) = Json.parseToJsonElement(Fixtures.text(name)).jsonObject["result"]!!.jsonObject

    private fun first(name: String): JsonObject = result(name)["list"]!!.jsonArray.first().jsonObject

    @Test
    fun `a usdt perpetual is a perpetual in its base coin, one unit a contract, with its steps`() {
        val i =
            BybitMarketMapping.instrument(
                BybitMarketJson.instrument(first("instruments-linear-BTCUSDT.json"), "linear"),
            )

        assertThat(i.code).isEqualTo("BTCUSDT")
        assertThat(i.kind).isEqualTo(InstrumentKind.PERPETUAL)
        assertThat(i.currency).isEqualTo("USDT")
        assertThat(i.contractSize).isEqualByComparingTo("1")
        assertThat(i.tickSize).isEqualTo(BigDecimal("0.10"))
        assertThat(i.volumeStep).isEqualTo(BigDecimal("0.001"))
        assertThat(i.volumeMin).isEqualTo(BigDecimal("0.001"))
        assertThat(i.expiryMs).isNull()
    }

    @Test
    fun `a dated contract is a future expiring at its delivery time, and a usdc perpetual settles in usdc`() {
        val future =
            BybitMarketMapping.instrument(
                BybitMarketJson.instrument(first("instruments-linear-future.json"), "linear"),
            )
        val usdc =
            BybitMarketMapping.instrument(
                BybitMarketJson.instrument(first("instruments-linear-usdc-perp.json"), "linear"),
            )

        assertThat(future.kind).isEqualTo(InstrumentKind.FUTURE)
        assertThat(future.expiryMs).isEqualTo(1_793_347_200_000L)
        assertThat(usdc.code).isEqualTo("BTCPERP")
        assertThat(usdc.currency).isEqualTo("USDC")
    }

    @Test
    fun `a spot pair is spot, quoted in its quote coin, stepped by its base precision`() {
        val i =
            BybitMarketMapping.instrument(
                BybitMarketJson.instrument(first("instruments-spot-BTCUSDT.json"), "spot"),
            )

        assertThat(i.kind).isEqualTo(InstrumentKind.SPOT)
        assertThat(i.currency).isEqualTo("USDT")
        assertThat(i.volumeStep).isEqualTo(BigDecimal("0.000001"))
        assertThat(i.volumeMin).isEqualTo(BigDecimal("0.000001"))
        assertThat(i.tickSize).isEqualTo(BigDecimal("0.1"))
    }

    @Test
    fun `a contract type the mapping does not know is refused by name`() {
        val i =
            BybitMarketJson
                .instrument(
                    first("instruments-linear-BTCUSDT.json"),
                    "linear",
                ).copy(contractType = "InversePerpetual")

        assertThatThrownBy { BybitMarketMapping.instrument(i) }.hasMessageContaining("InversePerpetual")
    }

    @Test
    fun `a quote carries both sides, the mark and the index`() {
        val q = BybitMarketMapping.quote(BybitMarketJson.ticker(first("ticker-linear-BTCUSDT.json"), 7L))

        assertThat(q.symbol).isEqualTo("BTCUSDT")
        assertThat(q.bid).isEqualByComparingTo("87717.60")
        assertThat(q.ask).isEqualByComparingTo("87717.90")
        assertThat(q.bidSize).isEqualByComparingTo("0.006")
        assertThat(q.askSize).isEqualByComparingTo("0.012")
        assertThat(q.mark).isEqualByComparingTo("87717.60")
        assertThat(q.index).isEqualByComparingTo("85971.37")
        assertThat(q.timeMs).isEqualTo(7L)
    }

    @Test
    fun `a spot ticker has no sides, mark or index, so its quote holds none`() {
        val q = BybitMarketMapping.quote(BybitMarketJson.ticker(first("ticker-spot-BTCUSDT.json"), 7L))

        assertThat(q.mark).isNull()
        assertThat(q.index).isNull()
        assertThat(q.bid).isEqualByComparingTo("85904.5")
    }

    @Test
    fun `a tape print keeps the taker's side and a liquidation reports the order that closed the position`() {
        val longLiquidated = BybitPrint("1", 1L, BigDecimal("85000"), BigDecimal("0.5"), "Buy")
        val shortLiquidated = longLiquidated.copy(side = "Sell")

        assertThat(BybitMarketMapping.print(longLiquidated).side).isEqualTo(Side.BUY)
        assertThat(BybitMarketMapping.liquidation(longLiquidated).side).isEqualTo(Side.SELL)
        assertThat(BybitMarketMapping.liquidation(shortLiquidated).side).isEqualTo(Side.BUY)
        assertThatThrownBy { BybitMarketMapping.print(longLiquidated.copy(side = "None")) }.hasMessageContaining("None")
    }

    @Test
    fun `depth keeps ten levels a side, best first, at the book's stamp`() {
        val book = BybitMarketJson.book(result("orderbook-spot.json"), 11L)
        val depth = BybitMarketMapping.depth(book)

        assertThat(depth.timeMs).isEqualTo(11L)
        assertThat(depth.bids).hasSize(10)
        assertThat(depth.bids.zipWithNext().all { (a, b) -> a.price > b.price }).isTrue
        assertThat(depth.asks.zipWithNext().all { (a, b) -> a.price < b.price }).isTrue
    }

    @Test
    fun `a funding rate carries no price, which bybit does not publish`() {
        val rate = BybitMarketMapping.fundingRate(BybitMarketJson.fundingRate(first("funding-history.json")))

        assertThat(rate.timeMs).isEqualTo(1_791_216_000_000L)
        assertThat(rate.rate).isEqualTo(BigDecimal("0.0001"))
        assertThat(rate.price).isNull()
    }
}
