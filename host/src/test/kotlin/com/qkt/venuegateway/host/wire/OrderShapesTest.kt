package com.qkt.venuegateway.host.wire

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.Tradability
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.vgp.WireMark
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderShapesTest {
    private val one = BigDecimal.ONE
    private val stopLimit =
        WireSubmit("a-1", "BTC_USDC-PERPETUAL", "buy", "stop_limit", "1", "84100", "84000", "day")

    @Test
    fun `stop orders need exactly the prices their type names`() {
        val order = WireMapping.newOrder(stopLimit)
        val stop = WireMapping.newOrder(stopLimit.copy(type = "stop", limitPrice = null))

        assertThat(order.type to order.timeInForce).isEqualTo(OrderType.STOP_LIMIT to TimeInForce.DAY)
        assertThat(order.limitPrice to order.stopPrice).isEqualTo(BigDecimal("84100") to BigDecimal("84000"))
        assertThat(stop.stopPrice).isEqualByComparingTo("84000")
        assertThatThrownBy { WireMapping.newOrder(stopLimit.copy(stopPrice = null)) }
            .hasMessage("stop_price is missing or invalid")
        assertThatThrownBy { WireMapping.newOrder(stopLimit.copy(type = "limit")) }
            .hasMessage("stop_price is missing or invalid")
        assertThatThrownBy { WireMapping.newOrder(stopLimit.copy(stopPrice = "soon")) }
            .hasMessage("stop_price 'soon' is not a decimal")
    }

    @Test
    fun `a blank id or symbol is refused by name`() {
        assertThatThrownBy { WireMapping.newOrder(stopLimit.copy(clientOrderId = " ")) }
            .hasMessage("client_order_id is missing or invalid")
        assertThatThrownBy { WireMapping.newOrder(stopLimit.copy(symbol = "")) }
            .hasMessage("symbol is missing or invalid")
        assertThat(WireMapping.newOrder(stopLimit.copy(clientOrderId = "x".repeat(WireMapping.MAX_ID))).clientOrderId)
            .hasSize(64)
    }

    @Test
    fun `an order the venue ended writes its fill price and reason, and a working one leaves both out`() {
        val venueOrder =
            VenueOrder(
                "a-1",
                null,
                "BTC_USDC-PERPETUAL",
                Side.SELL,
                OrderType.STOP,
                BigDecimal("1"),
                null,
                BigDecimal("83000"),
                TimeInForce.GTC,
                true,
                OrderStatus.REJECTED,
                BigDecimal("0.5"),
                BigDecimal("83000.5"),
                "post only",
                1L,
                2L,
            )

        val ended = WireMapping.order(venueOrder)
        val working =
            WireMapping.order(venueOrder.copy(status = OrderStatus.WORKING, avgFillPrice = null, rejectReason = null))

        assertThat(listOf(ended.status, ended.avgFillPrice, ended.rejectReason, ended.stopPrice))
            .containsExactly("rejected", "83000.5", "post only", "83000")
        assertThat(listOf(working.venueOrderId, working.limitPrice, working.avgFillPrice, working.rejectReason))
            .containsOnlyNulls()
        assertThat(WireMapping.submitOf(ended)).isEqualTo(
            WireSubmit("a-1", "BTC_USDC-PERPETUAL", "sell", "stop", "1", null, "83000", "gtc", true),
        )
    }

    @Test
    fun `reads keep every decimal plain and name a restricted instrument's trading`() {
        val snapshot = AccountSnapshot("USD", BigDecimal("1E+3"), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)
        val account = WireReads.account(snapshot)
        val share =
            Instrument("AAPL", InstrumentKind.EQUITY, "USD", one, BigDecimal("0.01"), one, one)

        assertThat(account.balance).isEqualTo("1000")
        assertThat(account.initialMargin to account.maintenanceMargin).isEqualTo(null to null)
        assertThat(WireReads.instrument(share).trading).isNull()
        assertThat(WireReads.instrument(share.copy(trading = Tradability.CLOSE_ONLY)).trading).isEqualTo("close_only")
        assertThat(WireReads.mark(VenueMark(5L, null, BigDecimal("2E+1")))).isEqualTo(WireMark(5L, null, "20"))
    }
}
