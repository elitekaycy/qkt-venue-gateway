package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.kalshi.client.KalshiPortfolioJson
import java.math.BigDecimal
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KalshiOrderMappingTest {
    private val cents = Fixtures.market("demo/events-KXNBA3D.json")
    private val tapered = Fixtures.market("public/events-KXPERSONPRESMAM.json")

    private fun order(
        side: Side,
        type: OrderType,
        tif: TimeInForce = TimeInForce.GTC,
        price: String? = "0.0400",
        quantity: String = "2.00",
    ) = NewOrder("label-1", cents.ticker, side, type, BigDecimal(quantity), price?.let(::BigDecimal), null, tif)

    private fun recorded(name: String) = KalshiPortfolioJson.order(Fixtures.json(name)["order"]!!.jsonObject)

    @Test
    fun `a limit buy is a bid and a sell an ask, at the yes price, its decimals as Kalshi takes them`() {
        val buy = KalshiOrderMapping.newOrder(order(Side.BUY, OrderType.LIMIT), cents, false)
        val sell = KalshiOrderMapping.newOrder(order(Side.SELL, OrderType.LIMIT, TimeInForce.IOC), cents, true)

        assertThat(buy.side).isEqualTo("bid")
        assertThat(buy.price.toPlainString()).isEqualTo("0.04")
        assertThat(buy.count.toPlainString()).isEqualTo("2")
        assertThat(buy.timeInForce).isEqualTo("good_till_canceled")
        assertThat(buy.clientOrderId).isEqualTo("label-1")
        assertThat(sell.side).isEqualTo("ask")
        assertThat(sell.timeInForce).isEqualTo("immediate_or_cancel")
        assertThat(sell.reduceOnly).isTrue
        val fok = KalshiOrderMapping.newOrder(order(Side.BUY, OrderType.LIMIT, TimeInForce.FOK), cents, false)
        assertThat(fok.timeInForce).isEqualTo("fill_or_kill")
    }

    @Test
    fun `a market order is an immediate-or-cancel limit at the worst price of the market's grid, even sent gtc`() {
        val buy = KalshiOrderMapping.newOrder(order(Side.BUY, OrderType.MARKET, price = null), cents, true)
        val sell = KalshiOrderMapping.newOrder(order(Side.SELL, OrderType.MARKET, price = null), tapered, false)

        assertThat(buy.price).isEqualByComparingTo("0.99")
        assertThat(buy.timeInForce).isEqualTo("immediate_or_cancel")
        assertThat(sell.price).isEqualByComparingTo("0.001")
        assertThat(KalshiOrderMapping.worst(Side.BUY, tapered)).isEqualByComparingTo("0.999")
        assertThat(KalshiOrderMapping.worst(Side.BUY, cents.copy(priceRanges = emptyList())))
            .isEqualByComparingTo("0.99")
        assertThat(KalshiOrderMapping.worst(Side.SELL, cents.copy(priceRanges = emptyList())))
            .isEqualByComparingTo("0.01")
        assertThat(KalshiOrderMapping.immediate(order(Side.BUY, OrderType.MARKET))).isTrue
        assertThat(KalshiOrderMapping.immediate(order(Side.BUY, OrderType.LIMIT))).isFalse
    }

    @Test
    fun `stops, day orders, a limit without a price and too fine a price or quantity are refused before Kalshi`() {
        listOf(
            order(Side.BUY, OrderType.STOP) to "no stop orders",
            order(Side.BUY, OrderType.STOP_LIMIT) to "no stop orders",
            order(Side.BUY, OrderType.LIMIT, TimeInForce.DAY) to "no day orders",
            order(Side.BUY, OrderType.LIMIT, price = null) to "a limit needs one",
            order(Side.BUY, OrderType.LIMIT, price = "0.04001") to "price of at most 4 decimals",
            order(Side.BUY, OrderType.LIMIT, quantity = "1.005") to "quantity of at most 2 decimals",
        ).forEach { (o, reason) ->
            assertThatThrownBy { KalshiOrderMapping.newOrder(o, cents, false) }
                .isInstanceOf(VenueRefusedException::class.java)
                .hasMessageContaining(reason)
        }
    }

    @Test
    fun `a resting order works at its yes price, amended to its filled plus resting quantity`() {
        val o = KalshiOrderMapping.order(recorded("private/order-amended.json"), null)

        assertThat(o.clientOrderId).isEqualTo("qkt-probe-rest")
        assertThat(o.venueOrderId).isEqualTo("01a1ae61-bf60-7752-859e-2842fa23b0ba")
        assertThat(o.side).isEqualTo(Side.BUY)
        assertThat(o.type).isEqualTo(OrderType.LIMIT)
        assertThat(o.status).isEqualTo(OrderStatus.WORKING)
        assertThat(o.quantity).isEqualByComparingTo("2")
        assertThat(o.limitPrice).isEqualByComparingTo("0.02")
        assertThat(o.filledQuantity).isEqualByComparingTo("0")
        assertThat(o.avgFillPrice).isNull()
        assertThat(o.timeInForce).isEqualTo(TimeInForce.GTC)
        assertThat(o.updatedAtMs).isGreaterThan(o.createdAtMs)
        assertThat(KalshiOrderMapping.order(recorded("private/order-cancelled.json"), null).status)
            .isEqualTo(OrderStatus.CANCELLED)
    }

    @Test
    fun `a filled market order reads back as sent, a bid at the yes cost and an ask at one less the no cost`() {
        val sent = KalshiSent(OrderType.MARKET, TimeInForce.IOC, true)
        val bid = KalshiOrderMapping.order(recorded("private/order-filled.json"), sent)
        val ask = KalshiOrderMapping.order(recorded("private/order-ask-filled.json"), null)

        assertThat(bid.status).isEqualTo(OrderStatus.FILLED)
        assertThat(bid.type).isEqualTo(OrderType.MARKET)
        assertThat(bid.limitPrice).isNull()
        assertThat(bid.timeInForce).isEqualTo(TimeInForce.IOC)
        assertThat(bid.reduceOnly).isTrue
        assertThat(bid.avgFillPrice).isEqualByComparingTo("0.04")
        assertThat(ask.side).isEqualTo(Side.SELL)
        assertThat(ask.avgFillPrice).isEqualByComparingTo("0.1")
    }

    @Test
    fun `an order status Kalshi does not document fails, and an order without a label is named by its id`() {
        val o = recorded("private/order-filled.json")

        assertThatThrownBy { KalshiOrderMapping.status(o.copy(status = "pending")) }
            .hasMessageContaining("status pending")
        assertThat(KalshiOrderMapping.order(o.copy(clientOrderId = null), null).clientOrderId).isEqualTo(o.orderId)
    }
}
