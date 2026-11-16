package com.qkt.vgp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WireOrderTest {
    private val order =
        WireOrder(
            "a-1",
            "v-1",
            "BTC-PERPETUAL",
            "buy",
            "stop_limit",
            "1",
            "84000",
            "83900",
            "gtc",
            true,
            "cancelled",
            "0.5",
            "84000.5",
            "post only",
            1_000L,
            2_000L,
        )
    private val cost = WireCost("commission", "0.01", "USDC")
    private val fill = WireFill("a-1", "v-1", "f-1", "BTC-PERPETUAL", "buy", "0.5", "84000", 1_500L, listOf(cost))
    private val settlement = WireSettlement("BTC-27SEP26", "84000", 3_000L, listOf(cost))

    @Test
    fun `an order reads back field by field under its wire names`() {
        val json =
            wireShape(
                order,
                order.copy(),
                order.copy(clientOrderId = "a-2"),
                order.copy(venueOrderId = null),
                order.copy(symbol = "ETH-PERPETUAL"),
                order.copy(side = "sell"),
                order.copy(type = "limit"),
                order.copy(quantity = "2"),
                order.copy(limitPrice = null),
                order.copy(stopPrice = null),
                order.copy(timeInForce = "ioc"),
                order.copy(reduceOnly = false),
                order.copy(status = "working"),
                order.copy(filledQuantity = "0"),
                order.copy(avgFillPrice = null),
                order.copy(rejectReason = null),
                order.copy(createdAt = 1L),
                order.copy(updatedAt = 1L),
            )
        wireShape(WireOrders(listOf(order)), WireOrders(listOf(order.copy())), WireOrders(emptyList()))

        assertThat(json.keys).contains(
            "client_order_id",
            "venue_order_id",
            "limit_price",
            "stop_price",
            "time_in_force",
            "reduce_only",
            "filled_quantity",
            "avg_fill_price",
            "reject_reason",
            "created_at",
            "updated_at",
        )
        refusesEmpty<WireOrder>()
        refusesEmpty<WireOrders>()
    }

    @Test
    fun `an order read without its optional fields is not reduce-only and has filled nothing`() {
        val text =
            """{"client_order_id":"a","symbol":"X","side":"buy","type":"market","quantity":"1",""" +
                """"time_in_force":"gtc","status":"working","created_at":1,"updated_at":2}"""

        val read = Json.decodeFromString(WireOrder.serializer(), text)

        assertThat(read.reduceOnly).isFalse
        assertThat(read.filledQuantity).isEqualTo("0")
        assertThat(listOf(read.venueOrderId, read.limitPrice, read.stopPrice, read.avgFillPrice, read.rejectReason))
            .containsOnlyNulls()
    }

    @Test
    fun `a submit reads back, not reduce-only unless it says so`() {
        val submit = WireSubmit("a-1", "BTC-PERPETUAL", "sell", "stop_limit", "1", "84000", "84100", "gtc", true)

        wireShape(
            submit,
            submit.copy(),
            submit.copy(clientOrderId = "a-2"),
            submit.copy(symbol = "ETH-PERPETUAL"),
            submit.copy(side = "buy"),
            submit.copy(type = "market"),
            submit.copy(quantity = "2"),
            submit.copy(limitPrice = null),
            submit.copy(stopPrice = null),
            submit.copy(timeInForce = "fok"),
            submit.copy(reduceOnly = false),
        )

        assertThat(WireSubmit("a", "X", "buy", "market", "1", timeInForce = "gtc").reduceOnly).isFalse
        refusesEmpty<WireSubmit>()
    }

    @Test
    fun `fills, their costs and settlements read back, costing nothing by default`() {
        wireShape(cost, cost.copy(), cost.copy(kind = "funding"), cost.copy(amount = "1"), cost.copy(currency = "BTC"))
        val json =
            wireShape(
                fill,
                fill.copy(),
                fill.copy(clientOrderId = "a-2"),
                fill.copy(venueOrderId = null),
                fill.copy(fillId = "f-2"),
                fill.copy(symbol = "ETH-PERPETUAL"),
                fill.copy(side = "sell"),
                fill.copy(quantity = "1"),
                fill.copy(price = "1"),
                fill.copy(time = 1L),
                fill.copy(costs = emptyList()),
            )
        wireShape(
            settlement,
            settlement.copy(),
            settlement.copy(symbol = "BTC-25DEC26"),
            settlement.copy(price = "1"),
            settlement.copy(time = 1L),
            settlement.copy(costs = emptyList()),
        )
        wireShape(WireDeals(listOf(fill)), WireDeals(listOf(fill.copy())), WireDeals(emptyList()))
        wireShape(WireSettlements(listOf(settlement)), WireSettlements(listOf(settlement)), WireSettlements(listOf()))

        assertThat(json.keys).contains("fill_id", "client_order_id", "venue_order_id")
        assertThat(WireFill("a", null, "f", "X", "buy", "1", "2", 3L).costs).isEmpty()
        assertThat(WireSettlement("X", "1", 2L).costs).isEmpty()
        refusesEmpty<WireCost>()
        refusesEmpty<WireFill>()
        refusesEmpty<WireSettlement>()
        refusesEmpty<WireDeals>()
        refusesEmpty<WireSettlements>()
    }

    @Test
    fun `a stream event reads back, and a reset carries neither time nor data`() {
        val event = WireEvent("s-1", 3L, "order", 1_000L, JsonPrimitive("x"))

        wireShape(
            event,
            event.copy(),
            event.copy(stream = "s-2"),
            event.copy(seq = 4L),
            event.copy(type = "fill"),
            event.copy(time = null),
            event.copy(data = null),
        )

        assertThat(written(WireEvent("s-1", 3L, "reset"))).isEqualTo("""{"stream":"s-1","seq":3,"type":"reset"}""")
        refusesEmpty<WireEvent>()
    }

    @Test
    fun `an error body reads back as the envelope every non-2xx response carries`() {
        val error = WireError("venue_rejected", "post only")
        val body = WireErrorBody(error)

        wireShape(error, error.copy(), error.copy(code = "conflict"), error.copy(message = "other"))
        wireShape(body, body.copy(), WireErrorBody(error.copy(code = "unsupported")))

        assertThat(written(body)).isEqualTo("""{"error":{"code":"venue_rejected","message":"post only"}}""")
        refusesEmpty<WireError>()
        refusesEmpty<WireErrorBody>()
    }
}
