package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitPublicTrade
import com.qkt.venuegateway.deribit.client.recordedObject
import com.qkt.venuegateway.deribit.market.DeribitTape
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeribitLiquidationMarksTest {
    private val trade: DeribitPublicTrade = DeribitJson.publicTrades(recordedObject("trades-from-time.json")).first()

    @Test
    fun `a print liquidated the taker's side for T, the maker's for M, both for MT and nobody for none`() {
        val buy = trade.copy(direction = "buy")
        val sell = trade.copy(direction = "sell")

        assertThat(DeribitTape.liquidatedSides(buy.copy(liquidation = null))).isEmpty()
        assertThat(DeribitTape.liquidatedSides(buy.copy(liquidation = "T"))).containsExactly(Side.BUY)
        assertThat(DeribitTape.liquidatedSides(sell.copy(liquidation = "T"))).containsExactly(Side.SELL)
        assertThat(DeribitTape.liquidatedSides(buy.copy(liquidation = "M"))).containsExactly(Side.SELL)
        assertThat(DeribitTape.liquidatedSides(sell.copy(liquidation = "M"))).containsExactly(Side.BUY)
        assertThat(DeribitTape.liquidatedSides(buy.copy(liquidation = "MT"))).containsExactly(Side.BUY, Side.SELL)
        assertThat(DeribitTape.liquidatedSides(sell.copy(liquidation = "MT"))).containsExactly(Side.SELL, Side.BUY)
    }

    @Test
    fun `a liquidation mark deribit adds later is refused naming the trade and the mark`() {
        assertThatThrownBy { DeribitTape.liquidatedSides(trade.copy(liquidation = "X")) }
            .hasMessageContaining(trade.tradeId)
            .hasMessageContaining("mark X")
    }
}
