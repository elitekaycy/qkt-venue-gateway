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

class AlpacaDeskArmsTest {
    private fun desk(
        routes: Map<String, List<String>>,
        extendedHours: Boolean = false,
    ) = DeskFixture.desk(routes, extendedHours)

    private fun share(
        type: OrderType,
        limit: String? = null,
        stop: String? = null,
        tif: TimeInForce = TimeInForce.DAY,
    ) = NewOrder(
        "qkt-1",
        "AAPL",
        Side.BUY,
        type,
        BigDecimal.ONE,
        limit?.let(::BigDecimal),
        stop?.let(::BigDecimal),
        tif,
    )

    @Test
    fun `a stop carries its stop price, a stop-limit both prices, and a market neither`() {
        val (desk, http) = desk(mapOf("POST /v2/orders" to listOf("order-limit-accepted.json")))

        desk.place(share(OrderType.STOP, stop = "1.50"))
        val stop = DeskFixture.body(http)
        desk.place(share(OrderType.STOP_LIMIT, limit = "1.00", stop = "1.50"))
        val stopLimit = DeskFixture.body(http)
        desk.place(share(OrderType.MARKET))
        val market = DeskFixture.body(http)

        assertThat(stop.text("stop_price")).isEqualTo("1.50")
        assertThat(stop.containsKey("limit_price")).isFalse
        assertThat(stopLimit.text("stop_price")).isEqualTo("1.50")
        assertThat(stopLimit.text("limit_price")).isEqualTo("1.00")
        assertThat(market.containsKey("stop_price")).isFalse
        assertThat(market.containsKey("limit_price")).isFalse
    }

    @Test
    fun `extended hours go on a share's limit only, never on its stop or market, when the account chose them`() {
        val (on, http) = desk(mapOf("POST /v2/orders" to listOf("order-limit-accepted.json")), extendedHours = true)

        on.place(share(OrderType.MARKET))
        assertThat(DeskFixture.body(http).containsKey("extended_hours")).isFalse
        on.place(share(OrderType.STOP, stop = "1.50"))
        assertThat(DeskFixture.body(http).containsKey("extended_hours")).isFalse
        on.place(share(OrderType.LIMIT, limit = "1.00"))
        assertThat(DeskFixture.body(http).containsKey("extended_hours")).isTrue
        val (off, offHttp) = desk(mapOf("POST /v2/orders" to listOf("order-limit-accepted.json")))
        off.place(share(OrderType.LIMIT, limit = "1.00"))
        assertThat(DeskFixture.body(offHttp).containsKey("extended_hours")).isFalse
    }

    @Test
    fun `an order already final is not cancelled again`() {
        val cancelled = Fixtures.obj("order-by-label-cancelled.json")
        val (desk, http) = desk(mapOf("GET /v2/orders:by_client_order_id" to listOf("order-by-label-cancelled.json")))

        desk.cancel(cancelled.text("client_order_id")!!)

        assertThat(http.sent.none { it.method == "DELETE" }).isTrue
        assertThat(desk.byLabel(cancelled.text("client_order_id")!!)!!.status).isEqualTo(OrderStatus.CANCELLED)
    }

    @Test
    fun `a replace sends every figure the change names, and only those`() {
        val original = Fixtures.obj("order-by-label-after-replace.json")
        val replacement = Fixtures.obj("order-crypto-replaced.json")
        val routes =
            mapOf(
                "GET /v2/orders:by_client_order_id" to
                    listOf("order-crypto-limit.json", "order-by-label-after-replace.json"),
                "PATCH /v2/orders/${original.text("id")}" to listOf("order-crypto-replaced.json"),
                "GET /v2/orders/${replacement.text("id")}" to listOf("order-crypto-replaced.json"),
            )
        val (desk, http) = desk(routes)

        desk.modify(
            original.text("client_order_id")!!,
            OrderChange(BigDecimal("2"), BigDecimal("60100"), BigDecimal("59000")),
        )
        val all = DeskFixture.body(http)
        val (bare, bareHttp) = desk(routes)
        bare.modify(original.text("client_order_id")!!, OrderChange())
        val none = DeskFixture.body(bareHttp)

        assertThat(
            listOf("qty", "limit_price", "stop_price").map { all.text(it) },
        ).containsExactly("2", "60100", "59000")
        assertThat(listOf("qty", "limit_price", "stop_price").map { none.text(it) }).containsOnlyNulls()
        assertThat(none.text("client_order_id")).isNotNull
    }

    @Test
    fun `a reduce-only buy closes a short, and is refused against a long`() {
        val buyToCover =
            NewOrder(
                "qkt-c",
                "BTCUSD",
                Side.BUY,
                OrderType.MARKET,
                BigDecimal("0.5"),
                null,
                null,
                TimeInForce.GTC,
                true,
            )
        val (short, http) =
            DeskFixture.desk(mapOf("POST /v2/orders" to listOf("order-crypto-market.json")), held = BigDecimal("-0.2"))
        val (long, _) = DeskFixture.desk(emptyMap(), held = BigDecimal("0.2"))

        short.place(buyToCover)

        assertThat(DeskFixture.body(http).text("qty")).isEqualTo("0.2")
        assertThatThrownBy { long.place(buyToCover) }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("no position")
    }
}
