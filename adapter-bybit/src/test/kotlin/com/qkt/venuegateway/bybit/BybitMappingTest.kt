package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.bybit.client.BybitExecution
import com.qkt.venuegateway.bybit.client.BybitOrder
import com.qkt.venuegateway.bybit.client.BybitPrivateJson
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitMappingTest {
    private fun items(name: String): List<JsonObject> {
        val root = Json.parseToJsonElement(Fixtures.text("private/$name")).jsonObject
        val list = root["result"]?.jsonObject?.get("list") ?: root["data"]!!
        return list.jsonArray.map { it.jsonObject }
    }

    private fun order(name: String): BybitOrder = BybitPrivateJson.order(items(name).first())

    private fun executions(name: String): List<BybitExecution> = items(name).map(BybitPrivateJson::execution)

    @Test
    fun `a resting limit is working under its label, with its price, nothing filled`() {
        val o = BybitMapping.order(order("order-limit-new.json"))!!

        assertThat(o.clientOrderId).isEqualTo("rec-rest-1")
        assertThat(o.type).isEqualTo(OrderType.LIMIT)
        assertThat(o.status).isEqualTo(OrderStatus.WORKING)
        assertThat(o.side).isEqualTo(Side.BUY)
        assertThat(o.quantity).isEqualByComparingTo("0.001")
        assertThat(o.limitPrice).isNotNull
        assertThat(o.stopPrice).isNull()
        assertThat(o.filledQuantity).isZero
        assertThat(o.avgFillPrice).isNull()
        assertThat(o.timeInForce).isEqualTo(TimeInForce.GTC)
    }

    @Test
    fun `an amended order carries its new quantity and price, and a cancelled one is cancelled`() {
        val amended = BybitMapping.order(order("order-limit-amended.json"))!!
        val cancelled = BybitMapping.order(order("order-limit-cancelled.json"))!!

        assertThat(amended.quantity).isEqualTo(BigDecimal("0.002"))
        assertThat(amended.limitPrice).isEqualTo(BigDecimal("77382.3"))
        assertThat(amended.updatedAtMs).isEqualTo(1_791_270_739_938L)
        assertThat(cancelled.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(
            BybitMapping.order(order("order-history-limit-cancelled.json"))!!.status,
        ).isEqualTo(OrderStatus.CANCELLED)
    }

    @Test
    fun `a market order larger than the band ends cancelled with what filled, at its average, as a market order`() {
        val o = BybitMapping.order(order("order-market-overrun.json"))!!

        assertThat(o.type).isEqualTo(OrderType.MARKET)
        assertThat(o.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(o.quantity).isEqualByComparingTo("0.4")
        assertThat(o.filledQuantity).isEqualTo(BigDecimal("0.158"))
        assertThat(o.avgFillPrice).isEqualTo(BigDecimal("86516.6879"))
        assertThat(o.limitPrice).describedAs("bybit's protection price is not a limit").isNull()
        assertThat(o.rejectReason).isNull()
        assertThat(o.timeInForce).isEqualTo(TimeInForce.IOC)
    }

    @Test
    fun `the overrun's executions are unique fills adding up to what filled, each with its commission`() {
        val fills = executions("executions-market-overrun.json").mapNotNull { BybitMapping.fill(it, "USDT") }
        val pushed = executions("ws-execution-market-overrun.json").mapNotNull { BybitMapping.fill(it, "USDT") }

        assertThat(fills).hasSize(32)
        assertThat(fills.map { it.fillId }).doesNotHaveDuplicates()
        assertThat(fills.fold(BigDecimal.ZERO) { sum, f -> sum + f.quantity }).isEqualByComparingTo("0.158")
        assertThat(pushed.map { it.fillId }).containsExactlyInAnyOrderElementsOf(fills.map { it.fillId })
        val first = fills.first { it.fillId == "e77dd02a-e1e9-54a3-b0df-d9227b1e4cbf" }
        assertThat(first.clientOrderId).isEqualTo("rec-overrun-1")
        assertThat(first.timeMs).isEqualTo(1_791_270_154_162L)
        assertThat(first.costs.single().kind).isEqualTo(CostKind.COMMISSION)
        assertThat(first.costs.single().currency).isEqualTo("USDT")
        assertThat(BybitMapping.order(BybitPrivateJson.order(items("ws-order-market-overrun.json").single()))!!.status)
            .isEqualTo(OrderStatus.CANCELLED)
    }

    @Test
    fun `a stop is a stop before it fires, while it fires, once filled, and cancelled before firing`() {
        val untriggered = BybitMapping.order(order("order-stop-untriggered.json"))!!
        val triggered = BybitMapping.order(order("ws-order-stop-triggered.json"))!!
        val fired = BybitMapping.order(order("order-stop-fired.json"))!!
        val deactivated = BybitMapping.order(order("order-stop-deactivated.json"))!!

        assertThat(listOf(untriggered, triggered, fired, deactivated).map { it.type }).containsOnly(OrderType.STOP)
        assertThat(untriggered.status).isEqualTo(OrderStatus.WORKING)
        assertThat(untriggered.stopPrice).isEqualTo(BigDecimal("128986.7"))
        assertThat(triggered.status).isEqualTo(OrderStatus.WORKING)
        assertThat(fired.status).isEqualTo(OrderStatus.FILLED)
        assertThat(fired.clientOrderId).isEqualTo("rec-stop-down")
        assertThat(fired.side).isEqualTo(Side.SELL)
        assertThat(fired.stopPrice).isEqualTo(BigDecimal("2739.61"))
        assertThat(fired.avgFillPrice).isEqualTo(BigDecimal("2739.59"))
        assertThat(deactivated.status).isEqualTo(OrderStatus.CANCELLED)
    }

    @Test
    fun `a fired stop's execution is a fill of the stop's label, fee charged in the settle coin`() {
        val fill = BybitMapping.fill(executions("ws-execution-stop-fired.json").single(), "USDT")!!

        assertThat(fill.clientOrderId).isEqualTo("rec-stop-down")
        assertThat(fill.fillId).isEqualTo("85406bdb-e365-5031-b5f0-5d60da7ebaf6")
        assertThat(fill.price).isEqualTo(BigDecimal("2739.59"))
        assertThat(fill.quantity).isEqualTo(BigDecimal("0.01"))
        assertThat(fill.costs.single().amount).isEqualTo(BigDecimal("0.01095836"))
        assertThat(fill.timeMs).isEqualTo(1_791_270_948_650L)
    }

    @Test
    fun `only a trade is a fill and only funding is funding`() {
        val trade = executions("ws-execution-stop-fired.json").single()

        assertThat(BybitMapping.funding(trade, "USDT")).isNull()
        listOf("BustTrade", "AdlTrade", "Delivery", "Settle", "Funding").forEach {
            assertThat(BybitMapping.fill(trade.copy(execType = it), "USDT")).describedAs(it).isNull()
        }
        assertThat(
            BybitMapping.fill(trade.copy(execType = null), "USDT"),
        ).describedAs("a record naming no type").isNotNull
        assertThat(
            BybitMapping.fill(trade.copy(orderLinkId = null), "USDT"),
        ).describedAs("another client's order").isNull()
    }

    @Test
    fun `funding a held long paid is a positive charge on the long, the same pushed and read back`() {
        val read = BybitMapping.funding(executions("executions-funding.json").single(), "USDT")!!
        val pushed = BybitMapping.funding(executions("ws-execution-funding.json").single(), "USDT")!!

        assertThat(read).isEqualTo(pushed)
        assertThat(read.symbol).isEqualTo("BTCUSDT")
        assertThat(read.amount).isEqualTo(BigDecimal("0.0085907"))
        assertThat(read.position).isEqualTo(BigDecimal("0.001"))
        assertThat(read.currency).isEqualTo("USDT")
        assertThat(read.timeMs).isEqualTo(1_791_273_600_000L)
        assertThat(BybitMapping.fill(executions("ws-execution-funding.json").single(), "USDT")).isNull()
    }

    @Test
    fun `a held long is a positive position at bybit's average, and a flat slot is no position`() {
        val long = BybitMapping.position(BybitPrivateJson.position(items("position-long.json").single()))!!
        val flat = items("position-slots-one-way.json").map(BybitPrivateJson::position)

        assertThat(long.symbol).isEqualTo("BTCUSDT")
        assertThat(long.quantity).isEqualTo(BigDecimal("0.158"))
        assertThat(long.avgPrice).isEqualTo(BigDecimal("86516.68797468"))
        assertThat(long.ticket).isNull()
        assertThat(flat.mapNotNull(BybitMapping::position)).isEmpty()
    }

    @Test
    fun `the account is the coin's balance and equity with the unified account's margin`() {
        val root = Json.parseToJsonElement(Fixtures.text("private/wallet.json")).jsonObject["result"]!!.jsonObject
        val account = BybitMapping.account(BybitPrivateJson.wallet(root["list"]!!.jsonArray.first().jsonObject, "USDT"))

        assertThat(account.currency).isEqualTo("USDT")
        assertThat(account.balance).isEqualByComparingTo("20000")
        assertThat(account.equity).isEqualByComparingTo("20000")
        assertThat(account.marginAvailable).isEqualByComparingTo("19993.54")
    }

    @Test
    fun `an order status the mapping does not know is refused by name`() {
        assertThatThrownBy {
            BybitMapping.order(order("order-limit-new.json").copy(orderStatus = "Paused"))
        }.hasMessageContaining("Paused")
    }
}
