package com.qkt.venuegateway.host.wire

import com.qkt.venuegateway.adapter.Cost
import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.host.wire.WireMapping.wire
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WireMappingTest {
    private val limit =
        WireSubmit("a-1.mg8q", "BTC_USDC-PERPETUAL", "sell", "limit", "0.10", "84000.5", null, "gtc", true)

    @Test
    fun `a submit maps exactly, keeping the scale of every decimal`() {
        val order = WireMapping.newOrder(limit)

        assertThat(order.side).isEqualTo(Side.SELL)
        assertThat(order.type).isEqualTo(OrderType.LIMIT)
        assertThat(order.timeInForce).isEqualTo(TimeInForce.GTC)
        assertThat(order.quantity).isEqualTo(BigDecimal("0.10"))
        assertThat(order.limitPrice).isEqualTo(BigDecimal("84000.5"))
        assertThat(order.reduceOnly).isTrue()
    }

    @Test
    fun `an unknown enum, a bad decimal, a missing level or a long id is an invalid request`() {
        assertThatThrownBy { WireMapping.newOrder(limit.copy(side = "short")) }.hasMessageContaining("side 'short'")
        assertThatThrownBy { WireMapping.newOrder(limit.copy(quantity = "1e")) }.hasMessageContaining("quantity '1e'")
        assertThatThrownBy { WireMapping.newOrder(limit.copy(quantity = "0")) }.hasMessageContaining("quantity")
        assertThatThrownBy { WireMapping.newOrder(limit.copy(limitPrice = null)) }.hasMessageContaining("limit_price")
        assertThatThrownBy { WireMapping.newOrder(limit.copy(type = "market")) }.hasMessageContaining("limit_price")
        assertThatThrownBy { WireMapping.newOrder(limit.copy(clientOrderId = "x".repeat(65))) }
            .hasMessageContaining("client_order_id")
    }

    @Test
    fun `every neutral cost kind has the wire name the spec defines, and fills write plain decimals`() {
        assertThat(CostKind.entries.map { it.wire() })
            .containsExactly("commission", "exchange_fee", "delivery_fee", "funding", "swap")
        val fill =
            VenueFill(
                "a-1",
                "v1",
                "f1",
                "BTC_USDC-PERPETUAL",
                Side.BUY,
                BigDecimal("1E-1"),
                BigDecimal("84000"),
                5,
                listOf(Cost(CostKind.FUNDING, BigDecimal("0.5"), "USDC")),
            )

        val wire = WireMapping.fill(fill)
        assertThat(wire.quantity).isEqualTo("0.1")
        assertThat(wire.side).isEqualTo("buy")
        assertThat(wire.costs.single().kind).isEqualTo("funding")
    }
}
