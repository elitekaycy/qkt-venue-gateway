package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitPosition
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import com.qkt.venuegateway.deribit.client.DeribitTrade
import com.qkt.venuegateway.deribit.client.DeribitTransaction
import com.qkt.venuegateway.deribit.client.recordedObject
import com.qkt.venuegateway.deribit.client.recordedResult
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeribitMappingArmsTest {
    private val open: DeribitOrder =
        DeribitPrivateJson.order(recordedResult("private/buy-limit-open.json").jsonObject["order"]!!.jsonObject)
    private val trade: DeribitTrade =
        DeribitPrivateJson.trade(
            (recordedObject("private/user-trades-by-time.json")["trades"] as JsonArray).first().jsonObject,
        )
    private val future: DeribitPosition =
        DeribitPrivateJson.position(recordedObject("private/positions-future-linear.json"))
    private val log: DeribitTransaction =
        DeribitPrivateJson.transactionPage(recordedObject("private/transaction-log-funding.json")).first.first()
    private val ten = BigDecimal("10")

    @Test
    fun `an order Deribit holds for no label of the gateway's is no order of its own`() {
        assertThat(DeribitMapping.order(open.copy(label = null))).isNull()
    }

    @Test
    fun `an order is the type it was sent as, a fired stop keeping the type it fired as`() {
        fun type(
            sent: String?,
            now: String,
            triggered: Boolean?,
        ) = DeribitMapping.order(open.copy(originalOrderType = sent, orderType = now, triggered = triggered))!!.type

        assertThat(type(null, "limit", null)).isEqualTo(OrderType.LIMIT)
        assertThat(type(null, "limit", true)).isEqualTo(OrderType.STOP_LIMIT)
        assertThat(type(null, "market", false)).isEqualTo(OrderType.MARKET)
        assertThat(type(null, "market", true)).isEqualTo(OrderType.STOP)
        assertThat(type("stop_market", "market", true)).isEqualTo(OrderType.STOP)
        assertThat(type("stop_limit", "limit", true)).isEqualTo(OrderType.STOP_LIMIT)
        assertThatThrownBy { type(null, "trailing_stop", null) }.hasMessageContaining("trailing_stop")
    }

    @Test
    fun `prices belong to the order types that carry them`() {
        val price = BigDecimal("101")
        val trigger = BigDecimal("99")

        fun map(type: String) =
            DeribitMapping.order(
                open.copy(originalOrderType = type, orderType = type, price = price, triggerPrice = trigger),
            )!!

        assertThat(map("limit").limitPrice).isEqualTo(price)
        assertThat(map("limit").stopPrice).isNull()
        assertThat(map("market").limitPrice).isNull()
        assertThat(map("stop_market").stopPrice).isEqualTo(trigger)
        assertThat(map("stop_market").limitPrice).isNull()
        assertThat(map("stop_limit").limitPrice).isEqualTo(price)
        assertThat(map("stop_limit").stopPrice).isEqualTo(trigger)
    }

    @Test
    fun `an average price shows only once something filled, and a reason only on a rejection`() {
        val none = DeribitMapping.order(open.copy(averagePrice = ten, filledAmount = BigDecimal.ZERO))!!
        val some = DeribitMapping.order(open.copy(averagePrice = ten, filledAmount = BigDecimal("0.5")))!!
        val rejected = DeribitMapping.order(open.copy(state = "rejected", cancelReason = "post_only_reject"))!!
        val cancelled = DeribitMapping.order(open.copy(state = "cancelled", cancelReason = "user_request"))!!

        assertThat(none.avgFillPrice).isNull()
        assertThat(some.avgFillPrice).isEqualTo(ten)
        assertThat(rejected.rejectReason).isEqualTo("post_only_reject")
        assertThat(rejected.status).isEqualTo(OrderStatus.REJECTED)
        assertThat(cancelled.rejectReason).isNull()
    }

    @Test
    fun `every deribit order state and time in force maps, and one the mapping lacks is refused by name`() {
        mapOf(
            "open" to OrderStatus.WORKING,
            "untriggered" to OrderStatus.WORKING,
            "filled" to OrderStatus.FILLED,
            "cancelled" to OrderStatus.CANCELLED,
            "rejected" to OrderStatus.REJECTED,
        ).forEach { (state, status) ->
            assertThat(DeribitMapping.order(open.copy(state = state))!!.status).describedAs(state).isEqualTo(status)
        }
        mapOf(
            "good_til_cancelled" to TimeInForce.GTC,
            "immediate_or_cancel" to TimeInForce.IOC,
            "fill_or_kill" to TimeInForce.FOK,
            "good_til_day" to TimeInForce.DAY,
        ).forEach { (word, tif) ->
            assertThat(
                DeribitMapping.order(open.copy(timeInForce = word))!!.timeInForce,
            ).describedAs(word).isEqualTo(tif)
        }
        assertThatThrownBy { DeribitMapping.order(open.copy(state = "archived")) }.hasMessageContaining("archived")
        assertThatThrownBy { DeribitMapping.order(open.copy(timeInForce = "forever")) }.hasMessageContaining("forever")
    }

    @Test
    fun `funding is a perpetual's interest, charged as the negative of what Deribit credited`() {
        val credited = log.copy(instrument = "BTC_USDC-PERPETUAL", interestPl = BigDecimal("0.5"), type = "trade")

        val charged = DeribitMapping.funding(credited) { true }!!

        assertThat(charged.amount).isEqualByComparingTo("-0.5")
        assertThat(charged.position).describedAs("only a settlement says the position held").isNull()
        assertThat(DeribitMapping.funding(credited.copy(type = "settlement", position = ten)) { true }!!.position)
            .isEqualTo(ten)
    }

    @Test
    fun `a log row that is no funding of a perpetual maps to none`() {
        val row = log.copy(instrument = "BTC_USDC-PERPETUAL", interestPl = BigDecimal("0.5"))

        assertThat(DeribitMapping.funding(row.copy(instrument = null)) { true }).isNull()
        assertThat(DeribitMapping.funding(row.copy(interestPl = null)) { true }).isNull()
        assertThat(DeribitMapping.funding(row.copy(interestPl = BigDecimal.ZERO)) { true }).isNull()
        assertThat(DeribitMapping.funding(row) { false }).isNull()
    }

    @Test
    fun `a trade for no label of the gateway's is no fill, and one with no fee has no cost`() {
        assertThat(DeribitMapping.fill(trade.copy(label = null))).isNull()
        assertThat(DeribitMapping.fill(trade.copy(label = "c-1", fee = BigDecimal.ZERO))!!.costs).isEmpty()
        assertThat(DeribitMapping.fill(trade.copy(label = "c-1", fee = BigDecimal("0.2")))!!.costs).hasSize(1)
    }

    @Test
    fun `a flat position is none, a short is negative, and an option is held in its own size`() {
        val short = DeribitMapping.position(future.copy(direction = "sell", sizeCurrency = BigDecimal("2")))!!
        val long = DeribitMapping.position(future.copy(direction = "buy", sizeCurrency = BigDecimal("-2")))!!
        val option =
            DeribitMapping.position(
                future.copy(kind = "option", size = BigDecimal("3"), sizeCurrency = null),
            )!!

        assertThat(DeribitMapping.position(future.copy(sizeCurrency = BigDecimal.ZERO))).isNull()
        assertThat(short.quantity).isEqualByComparingTo("-2")
        assertThat(long.quantity).isEqualByComparingTo("2")
        assertThat(option.quantity).isEqualByComparingTo("3")
        assertThatThrownBy { DeribitMapping.position(future.copy(sizeCurrency = null)) }
            .hasMessageContaining("size_currency")
    }

    @Test
    fun `an order to send is a market, limit or stop-limit, a limit carrying its price and a stop its trigger`() {
        fun send(
            type: OrderType,
            tif: TimeInForce = TimeInForce.GTC,
            side: Side = Side.SELL,
        ) = DeribitMapping.newOrder(
            NewOrder("c-1", "BTC_USDC-PERPETUAL", side, type, ten, BigDecimal("101"), BigDecimal("99"), tif, true),
            "mark_price",
        )

        val market = send(OrderType.MARKET)
        val limit = send(OrderType.LIMIT, TimeInForce.FOK)
        val stopLimit = send(OrderType.STOP_LIMIT, TimeInForce.IOC)

        assertThat(market.type).isEqualTo("market")
        assertThat(market.price).isNull()
        assertThat(market.triggerPrice).isNull()
        assertThat(market.trigger).isNull()
        assertThat(market.direction).isEqualTo("sell")
        assertThat(market.reduceOnly).isTrue
        assertThat(limit.type).isEqualTo("limit")
        assertThat(limit.price).isEqualByComparingTo("101")
        assertThat(limit.timeInForce).isEqualTo("fill_or_kill")
        assertThat(stopLimit.type).isEqualTo("stop_limit")
        assertThat(stopLimit.price).isEqualByComparingTo("101")
        assertThat(stopLimit.triggerPrice).isEqualByComparingTo("99")
        assertThat(stopLimit.trigger).isEqualTo("mark_price")
        assertThat(stopLimit.timeInForce).isEqualTo("immediate_or_cancel")
        assertThat(send(OrderType.LIMIT, TimeInForce.DAY).timeInForce).isEqualTo("good_til_day")
    }
}
