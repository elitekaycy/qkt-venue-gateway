package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Deribit's testnet answers, recorded 2026-10-01, mapped onto the neutral types. */
class DeribitMappingTest {
    private fun result(name: String): JsonElement =
        Json
            .parseToJsonElement(javaClass.getResource("/fixtures/private/$name")!!.readText())
            .jsonObject["response"]!!
            .jsonObject["result"]!!

    private fun order(name: String) = DeribitPrivateJson.order(result(name).jsonObject["order"]!!.jsonObject)

    private fun JsonElement.orders() = jsonArray.map { DeribitPrivateJson.order(it.jsonObject) }

    @Test
    fun `a resting limit is a working limit carrying its label, venue id and price`() {
        val order = DeribitMapping.order(order("buy-limit-open.json"))!!

        assertThat(order.clientOrderId).isEqualTo("kit-probe-1790866831")
        assertThat(order.venueOrderId).isEqualTo("USDC-210049234016384431")
        assertThat(order.side).isEqualTo(Side.BUY)
        assertThat(order.type).isEqualTo(OrderType.LIMIT)
        assertThat(order.status).isEqualTo(OrderStatus.WORKING)
        assertThat(order.limitPrice).isEqualByComparingTo("50461.9")
        assertThat(order.stopPrice).isNull()
        assertThat(order.avgFillPrice).isNull()
        assertThat(order.timeInForce).isEqualTo(TimeInForce.GTC)
    }

    @Test
    fun `a filled market order has no limit, though Deribit reports its protection cap as a price`() {
        val order = DeribitMapping.order(order("buy-market-filled.json"))!!

        assertThat(order.type).isEqualTo(OrderType.MARKET)
        assertThat(order.status).isEqualTo(OrderStatus.FILLED)
        assertThat(order.limitPrice).isNull()
        assertThat(order.avgFillPrice).isEqualByComparingTo("84058.7")
    }

    @Test
    fun `stops are working until they fire, and a fired stop is still the stop the client sent`() {
        val untriggered = DeribitMapping.order(order("buy-stop-market-untriggered.json"))!!
        val stopLimit = DeribitMapping.order(order("buy-stop-limit-untriggered.json"))!!
        val fired =
            DeribitMapping.order(
                result("order-state-by-label-stop-triggered.json").orders().single {
                    it.triggered ==
                        true
                },
            )!!

        assertThat(untriggered.type).isEqualTo(OrderType.STOP)
        assertThat(untriggered.status).isEqualTo(OrderStatus.WORKING)
        assertThat(untriggered.stopPrice).isEqualByComparingTo("126268.8")
        assertThat(untriggered.limitPrice).isNull()
        assertThat(stopLimit.type).isEqualTo(OrderType.STOP_LIMIT)
        assertThat(stopLimit.limitPrice).isEqualByComparingTo("126278.8")
        assertThat(fired.type).isEqualTo(OrderType.STOP)
        assertThat(fired.status).isEqualTo(OrderStatus.FILLED)
        assertThat(fired.stopPrice).isEqualByComparingTo("84096.0")
        assertThat(fired.limitPrice).isNull()
    }

    @Test
    fun `a day order and a cancelled order map their time in force and status`() {
        assertThat(DeribitMapping.order(order("buy-good-til-day.json"))!!.timeInForce).isEqualTo(TimeInForce.DAY)
        assertThat(DeribitMapping.order(result("order-state-by-label-cancelled.json").orders().single())!!.status)
            .isEqualTo(OrderStatus.CANCELLED)
    }

    @Test
    fun `trades become fills with the venue trade id and the fee as a commission, unlabelled ones are not ours`() {
        val trades = DeribitPrivateJson.tradePage(result("user-trades-by-time.json").jsonObject).items
        val fill = DeribitMapping.fill(trades.first())!!

        assertThat(fill.fillId).isEqualTo("USDC-55455987")
        assertThat(fill.clientOrderId).isEqualTo("kit-fill-1790866869-o")
        assertThat(fill.quantity).isEqualByComparingTo("0.0001")
        assertThat(fill.costs.single().kind).isEqualTo(CostKind.COMMISSION)
        assertThat(fill.costs.single().amount).isEqualByComparingTo("0.00420293")
        assertThat(fill.costs.single().currency).isEqualTo("USDC")
        assertThat(DeribitMapping.fill(trades.first().copy(label = null))).isNull()
        assertThat(DeribitMapping.order(order("buy-limit-open.json").copy(label = null))).isNull()
    }

    @Test
    fun `a future's quantity is its coin amount, an option's its size, sells negative, flat rows gone`() {
        val future = DeribitPrivateJson.position(result("positions-future-linear.json").jsonArray.single().jsonObject)
        val option = DeribitPrivateJson.position(result("positions-option.json").jsonArray.single().jsonObject)
        val flat = DeribitPrivateJson.position(result("positions-future-flat.json").jsonArray.single().jsonObject)

        assertThat(DeribitMapping.position(future)!!.quantity).isEqualByComparingTo("0.0001")
        assertThat(DeribitMapping.position(option)!!.quantity).isEqualByComparingTo("10000")
        assertThat(DeribitMapping.position(future.copy(direction = "sell"))!!.quantity).isEqualByComparingTo("-0.0001")
        assertThat(DeribitMapping.position(flat)).isNull()
        assertThatThrownBy {
            DeribitMapping.position(
                future.copy(sizeCurrency = null),
            )
        }.hasMessageContaining("size_currency")
    }

    @Test
    fun `an order to send names Deribit's type, trigger and time in force`() {
        fun send(
            type: OrderType,
            tif: TimeInForce,
        ) = DeribitMapping.newOrder(
            NewOrder(
                "c-1",
                "BTC_USDC-PERPETUAL",
                Side.SELL,
                type,
                BigDecimal("0.0002"),
                BigDecimal("80000"),
                BigDecimal("81000"),
                tif,
                true,
            ),
            "mark_price",
        )

        val stopLimit = send(OrderType.STOP_LIMIT, TimeInForce.DAY)
        assertThat(stopLimit.type).isEqualTo("stop_limit")
        assertThat(stopLimit.direction).isEqualTo("sell")
        assertThat(stopLimit.trigger).isEqualTo("mark_price")
        assertThat(stopLimit.triggerPrice).isEqualByComparingTo("81000")
        assertThat(stopLimit.price).isEqualByComparingTo("80000")
        assertThat(stopLimit.timeInForce).isEqualTo("good_til_day")
        assertThat(stopLimit.reduceOnly).isTrue
        val market = send(OrderType.MARKET, TimeInForce.IOC)
        assertThat(market.type).isEqualTo("market")
        assertThat(market.price).isNull()
        assertThat(market.trigger).isNull()
        assertThat(market.triggerPrice).isNull()
        assertThat(market.timeInForce).isEqualTo("immediate_or_cancel")
        assertThat(send(OrderType.LIMIT, TimeInForce.FOK).timeInForce).isEqualTo("fill_or_kill")
    }

    @Test
    fun `an order state or type Deribit adds later fails by name instead of being guessed`() {
        val open = order("buy-limit-open.json")
        assertThatThrownBy { DeribitMapping.order(open.copy(state = "archived")) }.hasMessageContaining("archived")
        assertThatThrownBy {
            DeribitMapping.order(open.copy(orderType = "trailing_stop"))
        }.hasMessageContaining("trailing_stop")
    }
}
