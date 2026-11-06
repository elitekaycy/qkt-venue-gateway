package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.bybit.client.BybitBook
import com.qkt.venuegateway.bybit.client.BybitInstrument
import com.qkt.venuegateway.bybit.client.BybitKline
import com.qkt.venuegateway.bybit.client.BybitMarketJson
import com.qkt.venuegateway.bybit.client.BybitPrint
import com.qkt.venuegateway.bybit.client.BybitTicker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitMarketMappingArmsTest {
    private val btc: BybitInstrument =
        BybitMarketJson.instrument(
            firstRecord("instruments-linear-BTCUSDT.json"),
            "linear",
        )
    private val one = BigDecimal.ONE

    @Test
    fun `a contract type bybit has and the gateway does not is refused by name`() {
        assertThatThrownBy { BybitMarketMapping.instrument(btc.copy(contractType = "InversePerpetual")) }
            .hasMessageContaining("InversePerpetual")
        assertThatThrownBy { BybitMarketMapping.instrument(btc.copy(contractType = null)) }
            .hasMessageContaining("BTCUSDT")
    }

    @Test
    fun `a spot category is spot whatever its contract type says, and only a future expires`() {
        assertThat(BybitMarketMapping.instrument(btc.copy(category = "spot", contractType = null)).kind)
            .isEqualTo(InstrumentKind.SPOT)
        val dated = btc.copy(contractType = "LinearFutures", deliveryMs = 1_793_347_200_000L)
        assertThat(BybitMarketMapping.instrument(dated).expiryMs).isEqualTo(1_793_347_200_000L)
        assertThat(BybitMarketMapping.instrument(btc.copy(deliveryMs = 5L)).expiryMs).isNull()
    }

    @Test
    fun `a side with no price or no size, or either at zero, is no quote on that side`() {
        val t = BybitTicker("BTCUSDT", 7L, bid = one, bidSize = one, ask = one, askSize = one)
        val empty = BybitMarketMapping.quote(t.copy(bid = null, askSize = BigDecimal.ZERO))
        val noSize = BybitMarketMapping.quote(t.copy(bidSize = null, ask = BigDecimal.ZERO))
        val full = BybitMarketMapping.quote(t)

        assertThat(listOf(empty.bid, empty.bidSize, empty.ask, empty.askSize)).containsExactly(null, null, null, null)
        assertThat(listOf(noSize.bid, noSize.bidSize, noSize.ask, noSize.askSize)).containsOnlyNulls()
        assertThat(full.bid).isEqualTo(one)
        assertThat(full.askSize).isEqualTo(one)
    }

    @Test
    fun `a candle with no volume has none, and a liquidation is the other side of the position it closed`() {
        val bar = BybitMarketMapping.bar(BybitKline(1L, one, one, one, one, null))
        val sold = BybitMarketMapping.liquidation(BybitPrint("a", 1L, one, one, "Sell"))
        val bought = BybitMarketMapping.liquidation(BybitPrint("b", 1L, one, one, "Buy"))

        assertThat(bar.volume).isZero
        assertThat(sold.side).isEqualTo(Side.BUY)
        assertThat(bought.side).isEqualTo(Side.SELL)
        assertThatThrownBy { BybitMarketMapping.print(BybitPrint("c", 1L, one, one, "Both")) }
            .hasMessageContaining("Both")
    }

    @Test
    fun `a book deeper than the gateway keeps is cut to its best levels`() {
        val levels = (1..30).map { BigDecimal(it) to one }
        val depth = BybitMarketMapping.depth(BybitBook(5L, levels, levels))

        assertThat(depth.bids).hasSize(depth.asks.size).hasSizeLessThan(30)
        assertThat(depth.bids.first().price).isEqualByComparingTo("1")
    }
}
