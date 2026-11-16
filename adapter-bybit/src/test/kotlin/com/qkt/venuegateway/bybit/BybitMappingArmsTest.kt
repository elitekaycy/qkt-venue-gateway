package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.bybit.client.BybitExecution
import com.qkt.venuegateway.bybit.client.BybitOrder
import com.qkt.venuegateway.bybit.client.BybitPosition
import com.qkt.venuegateway.bybit.client.BybitPrivateJson
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitMappingArmsTest {
    private val limit: BybitOrder = BybitPrivateJson.order(firstRecord("private/order-limit-new.json"))
    private val trade: BybitExecution = BybitPrivateJson.execution(firstRecord("private/executions-stop-fired.json"))
    private val position: BybitPosition = BybitPrivateJson.position(firstRecord("private/position-long.json"))

    @Test
    fun `an order sent by another client is no order of the gateway's`() {
        assertThat(BybitMapping.order(limit.copy(orderLinkId = null))).isNull()
    }

    @Test
    fun `a conditional limit is a stop-limit carrying both its price and its trigger`() {
        val o = BybitMapping.order(limit.copy(orderType = "Limit", triggerPrice = BigDecimal("90000")))!!

        assertThat(o.type).isEqualTo(OrderType.STOP_LIMIT)
        assertThat(o.limitPrice).isEqualTo(limit.price)
        assertThat(o.stopPrice).isEqualTo(BigDecimal("90000"))
    }

    @Test
    fun `an order marked by its stop type alone is a stop with no trigger price`() {
        val market = BybitMapping.order(limit.copy(orderType = "Market", stopOrderType = "StopLoss"))!!
        val limited = BybitMapping.order(limit.copy(orderType = "Limit", stopOrderType = "TakeProfit"))!!

        assertThat(market.type).isEqualTo(OrderType.STOP)
        assertThat(market.stopPrice).isNull()
        assertThat(market.limitPrice).isNull()
        assertThat(limited.type).isEqualTo(OrderType.STOP_LIMIT)
        assertThat(limited.stopPrice).isNull()
    }

    @Test
    fun `an order type bybit has and the gateway does not is refused by name`() {
        assertThatThrownBy { BybitMapping.order(limit.copy(orderType = "Iceberg")) }.hasMessageContaining("Iceberg")
    }

    @Test
    fun `a rejection carries bybit's reason, or a plain word when it gave none, and only a rejection has one`() {
        val said = BybitMapping.order(limit.copy(orderStatus = "Rejected", rejectReason = "EC_PerCancelRequest"))!!
        val silent = BybitMapping.order(limit.copy(orderStatus = "Rejected", rejectReason = null))!!
        val working = BybitMapping.order(limit.copy(orderStatus = "New", rejectReason = "EC_PerCancelRequest"))!!

        assertThat(said.status).isEqualTo(OrderStatus.REJECTED)
        assertThat(said.rejectReason).isEqualTo("EC_PerCancelRequest")
        assertThat(silent.rejectReason).isEqualTo("rejected")
        assertThat(working.rejectReason).isNull()
    }

    @Test
    fun `every bybit order state maps to working, filled, cancelled or rejected`() {
        val expected =
            mapOf(
                "Created" to OrderStatus.WORKING,
                "New" to OrderStatus.WORKING,
                "PartiallyFilled" to OrderStatus.WORKING,
                "Untriggered" to OrderStatus.WORKING,
                "Triggered" to OrderStatus.WORKING,
                "Active" to OrderStatus.WORKING,
                "Filled" to OrderStatus.FILLED,
                "Cancelled" to OrderStatus.CANCELLED,
                "PartiallyFilledCanceled" to OrderStatus.CANCELLED,
                "Deactivated" to OrderStatus.CANCELLED,
                "Rejected" to OrderStatus.REJECTED,
            )

        expected.forEach { (state, status) ->
            assertThat(
                BybitMapping.order(limit.copy(orderStatus = state))!!.status,
            ).describedAs(state).isEqualTo(status)
        }
    }

    @Test
    fun `time in force maps by bybit's names, post-only resting as good-till-cancel, and an unknown one is refused`() {
        val expected =
            mapOf(
                "GTC" to TimeInForce.GTC,
                "PostOnly" to TimeInForce.GTC,
                "IOC" to TimeInForce.IOC,
                "FOK" to TimeInForce.FOK,
            )

        expected.forEach { (word, tif) ->
            assertThat(
                BybitMapping.order(limit.copy(timeInForce = word))!!.timeInForce,
            ).describedAs(word).isEqualTo(tif)
        }
        assertThatThrownBy { BybitMapping.order(limit.copy(timeInForce = "RPI")) }.hasMessageContaining("RPI")
    }

    @Test
    fun `an average price shows only once something filled`() {
        val none = BybitMapping.order(limit.copy(avgPrice = BigDecimal("100"), cumExecQty = BigDecimal.ZERO))!!
        val some = BybitMapping.order(limit.copy(avgPrice = BigDecimal("100"), cumExecQty = BigDecimal("0.5")))!!

        assertThat(none.avgFillPrice).isNull()
        assertThat(some.avgFillPrice).isEqualTo(BigDecimal("100"))
    }

    @Test
    fun `a fill with no fee has no cost, and a fee is in the coin bybit names or else the settle coin`() {
        val free = BybitMapping.fill(trade.copy(execFee = BigDecimal.ZERO), "USDT")!!
        val named = BybitMapping.fill(trade.copy(feeCurrency = "BTC"), "USDT")!!
        val blank = BybitMapping.fill(trade.copy(feeCurrency = null), "USDC")!!
        val rebate = BybitMapping.fill(trade.copy(execFee = BigDecimal("-0.5"), execType = "Trade"), "USDT")!!

        assertThat(free.costs).isEmpty()
        assertThat(named.costs.single().currency).isEqualTo("BTC")
        assertThat(blank.costs.single().currency).isEqualTo("USDC")
        assertThat(rebate.costs.single().kind).isEqualTo(CostKind.COMMISSION)
        assertThat(rebate.costs.single().amount).isEqualTo(BigDecimal("-0.5"))
    }

    @Test
    fun `funding paid on a short is held as a negative position, in the coin named or the settle coin`() {
        val funding = trade.copy(execType = "Funding")

        val short = BybitMapping.funding(funding.copy(side = "Sell"), "USDT")!!
        val long = BybitMapping.funding(funding.copy(side = "Buy", feeCurrency = "BTC"), "USDT")!!

        assertThat(short.position).isEqualTo(funding.execQty.negate())
        assertThat(short.currency).isEqualTo(funding.feeCurrency ?: "USDT")
        assertThat(long.position).isEqualTo(funding.execQty)
        assertThat(long.currency).isEqualTo("BTC")
        assertThat(BybitMapping.funding(funding.copy(feeCurrency = null), "USDC")!!.currency).isEqualTo("USDC")
    }

    @Test
    fun `a held short is negative, hedge slots are tickets, and a slot the mapping does not know is refused`() {
        val short = BybitMapping.position(position.copy(side = "Sell", positionIdx = 2))!!
        val long = BybitMapping.position(position.copy(side = "Buy", positionIdx = 1))!!

        assertThat(short.quantity).isEqualTo(position.size.negate())
        assertThat(short.ticket).isEqualTo("short")
        assertThat(long.ticket).isEqualTo("long")
        assertThatThrownBy { BybitMapping.position(position.copy(positionIdx = 3)) }.hasMessageContaining("3")
    }

    @Test
    fun `a held position with no side, no average price or an unknown side is refused by name`() {
        assertThatThrownBy { BybitMapping.position(position.copy(side = null)) }.hasMessageContaining("no side")
        assertThatThrownBy { BybitMapping.position(position.copy(avgPrice = null)) }
            .hasMessageContaining("no average price")
        assertThatThrownBy { BybitMapping.position(position.copy(side = "Flat")) }.hasMessageContaining("Flat")
    }

    @Test
    fun `an unknown side in an order or an execution is refused by name`() {
        assertThatThrownBy { BybitMapping.order(limit.copy(side = "Both")) }.hasMessageContaining("Both")
        assertThatThrownBy { BybitMapping.fill(trade.copy(side = "Both"), "USDT") }.hasMessageContaining("Both")
        assertThat(Side.entries).contains(BybitMarketMapping.side("Buy"), BybitMarketMapping.side("Sell"))
    }
}
