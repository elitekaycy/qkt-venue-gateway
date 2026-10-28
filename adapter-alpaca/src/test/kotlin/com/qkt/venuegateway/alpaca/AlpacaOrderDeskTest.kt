package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.support.JsonReads.text
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AlpacaOrderDeskTest {
    private fun desk(
        routes: Map<String, List<String>>,
        extendedHours: Boolean = false,
        held: BigDecimal = BigDecimal.ZERO,
    ) = DeskFixture.desk(routes, extendedHours, held)

    private fun body(http: FixtureHttp) = DeskFixture.body(http)

    private fun limit(
        symbol: String = "AAPL",
        tif: TimeInForce = TimeInForce.DAY,
    ) = NewOrder("qkt-1", symbol, Side.BUY, OrderType.LIMIT, BigDecimal.ONE, BigDecimal("1.00"), null, tif)

    @Test
    fun `a limit is sent with the label as client_order_id and Alpaca's own words, and answers the working order`() {
        val (desk, http) = desk(mapOf("POST /v2/orders" to listOf("order-limit-accepted.json")))
        val order = desk.place(limit())
        val sent = body(http)
        assertThat(
            listOf("symbol", "qty", "side", "type", "time_in_force", "client_order_id", "limit_price").map {
                sent.text(it)
            },
        ).containsExactly("AAPL", "1", "buy", "limit", "day", "qkt-1", "1.00")
        assertThat(sent.containsKey("extended_hours")).isFalse
        assertThat(order.clientOrderId).isEqualTo("qkt-1")
        assertThat(order.status).isEqualTo(OrderStatus.WORKING)
    }

    @Test
    fun `extended hours go on a share's limit DAY and GTC orders only, and only when the account chose them`() {
        val (desk, http) =
            desk(
                mapOf(
                    "POST /v2/orders" to listOf("order-extended-hours.json", "order-extended-hours-gtc.json"),
                ),
                extendedHours = true,
            )
        desk.place(limit())
        assertThat(body(http).text("extended_hours")).isEqualTo("true")
        desk.place(limit(tif = TimeInForce.GTC))
        assertThat(body(http).text("extended_hours")).isEqualTo("true")
        desk.place(limit(tif = TimeInForce.IOC))
        assertThat(body(http).containsKey("extended_hours")).isFalse
        desk.place(limit(symbol = "BTCUSD", tif = TimeInForce.GTC))
        assertThat(body(http).text("symbol")).isEqualTo("BTC/USD")
        assertThat(body(http).containsKey("extended_hours")).isFalse
    }

    @Test
    fun `a reduce-only close is sent for at most the position, and refused when there is none to reduce`() {
        val close =
            NewOrder(
                "qkt-c",
                "BTCUSD",
                Side.SELL,
                OrderType.MARKET,
                BigDecimal("0.0002"),
                null,
                null,
                TimeInForce.GTC,
                true,
            )
        val (desk, http) =
            desk(
                mapOf("POST /v2/orders" to listOf("order-crypto-market.json")),
                held = BigDecimal("0.000199499"),
            )
        desk.place(close)
        assertThat(body(http).text("qty")).isEqualTo("0.000199499")
        val (flat, _) = desk(emptyMap())
        assertThatThrownBy {
            flat.place(close)
        }.isInstanceOf(VenueRefusedException::class.java).hasMessageContaining("no position")
    }

    @Test
    fun `a retried place whose label Alpaca already holds answers that order instead of a refusal`() {
        val (desk, _) =
            desk(
                mapOf(
                    "POST /v2/orders" to listOf("422:order-duplicate-label.json"),
                    "GET /v2/orders:by_client_order_id" to listOf("order-by-label-working.json"),
                ),
            )
        assertThat(desk.place(limit()).status).isEqualTo(OrderStatus.WORKING)
    }

    @Test
    fun `an order Alpaca refuses throws its reason and an unlisted code never reaches Alpaca`() {
        val (desk, http) = desk(mapOf("POST /v2/orders" to listOf("422:error-sub-penny.json")))
        assertThatThrownBy {
            desk.place(limit())
        }.isInstanceOf(VenueRefusedException::class.java).hasMessageContaining("sub-penny")
        assertThatThrownBy { desk.place(limit(symbol = "NOPE")) }.hasMessageContaining("lists no NOPE")
        assertThat(http.sent.count { it.method == "POST" }).isEqualTo(1)
        // A share is looked up by itself, once: the fourteen-thousand-share list is never read to place an order.
        assertThat(http.sent.count { it.path == "/v2/assets/AAPL" }).isEqualTo(1)
        assertThat(
            http.sent.none { it.path == "/v2/assets" && it.params.contains("asset_class" to "us_equity") },
        ).isTrue
    }

    @Test
    fun `a replace sends the label's next generation, and a lookup follows the chain to the newest order`() {
        val original = Fixtures.obj("order-by-label-after-replace.json")
        val replacement = Fixtures.obj("order-crypto-replaced.json")
        val (desk, http) =
            desk(
                mapOf(
                    "GET /v2/orders:by_client_order_id" to
                        listOf("order-crypto-limit.json", "order-by-label-after-replace.json"),
                    "PATCH /v2/orders/${original.text("id")}" to listOf("order-crypto-replaced.json"),
                    "GET /v2/orders/${replacement.text("id")}" to listOf("order-crypto-replaced.json"),
                ),
            )
        val label = original.text("client_order_id")!!
        // modify returns once a lookup by the label reaches the replacement: the host reads it back straight after.
        assertThat(desk.modify(label, OrderChange(limitPrice = BigDecimal("60100")))).isTrue
        assertThat(body(http).text("client_order_id")).isEqualTo("$label~r1")
        assertThat(body(http).text("limit_price")).isEqualTo("60100")
        val found = desk.byLabel(label)!!
        assertThat(found.venueOrderId).isEqualTo(replacement.text("id"))
        assertThat(found.clientOrderId).isEqualTo(label)
        assertThat(found.limitPrice).isEqualByComparingTo("60100")
    }

    @Test
    fun `a cancel waits until Alpaca reports the order final, and an unknown label is no error`() {
        val working = Fixtures.obj("order-by-label-working.json")
        val (desk, _) =
            desk(
                mapOf(
                    "GET /v2/orders:by_client_order_id" to
                        listOf(
                            "order-by-label-working.json",
                            "order-by-label-working.json",
                            "order-by-label-cancelled.json",
                        ),
                    "DELETE /v2/orders/${working.text("id")}" to listOf(""),
                    "GET /v2/orders" to listOf("orders-open.json"),
                ),
            )
        val label = working.text("client_order_id")!!
        desk.cancel(label)
        assertThat(desk.byLabel(label)?.status).isEqualTo(OrderStatus.CANCELLED)
        // Alpaca's open list still holds it for a moment after the cancel: the desk does not report it open.
        assertThat(desk.open()).isEmpty()
        val (none, _) = desk(mapOf("GET /v2/orders:by_client_order_id" to listOf("404:order-by-label-unknown.json")))
        none.cancel("qkt-never-placed")
        assertThat(none.byLabel("qkt-never-placed")).isNull()
        assertThat(none.modify("qkt-never-placed", OrderChange(limitPrice = BigDecimal.ONE))).isFalse
    }
}
