package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.support.JsonReads.instantMs
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.obj
import com.qkt.venuegateway.support.JsonReads.text
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A share stop refused for the shares its resting target holds (recorded on paper, MSFT) goes out with it as an OCO. */
class AlpacaExitPairsTest {
    private val target = Fixtures.array("orders-open-exit-target.json").single()
    private val targetId = target.need("id")
    private val placedAt = target.instantMs("created_at")!!
    private val stopLabel = "dsl-pair-sl.mi1783a"

    private fun stop(
        label: String = stopLabel,
        type: OrderType = OrderType.STOP,
        symbol: String = "MSFT",
    ) = NewOrder(label, symbol, Side.SELL, type, BigDecimal("2"), BigDecimal("299"), BigDecimal("300"), TimeInForce.GTC)

    private fun routes(vararg posts: String) =
        mapOf(
            "POST /v2/orders" to listOf("403:error-exit-stop-held-qty.json", *posts),
            "GET /v2/orders" to listOf("orders-open-exit-target.json"),
            "DELETE /v2/orders/$targetId" to listOf(""),
            "GET /v2/orders/$targetId" to listOf("order-exit-target-cancelled.json"),
        )

    private fun desk(
        routes: Map<String, List<String>>,
        clock: () -> Long = { placedAt + 5_000 },
    ) = DeskFixture.desk(routes, clock = clock)

    @Test
    fun `a stop refused for shares its target holds goes out with it as one oco, answered as the stop leg`() {
        val (desk, http) = desk(routes("order-exit-oco.json"))
        val placed = desk.place(stop())
        assertThat(placed.clientOrderId).isEqualTo(stopLabel)
        assertThat(placed.type).isEqualTo(OrderType.STOP)
        assertThat(placed.status).isEqualTo(OrderStatus.WORKING)
        assertThat(placed.stopPrice).isEqualByComparingTo("300")
        val oco = DeskFixture.body(http)
        assertThat(listOf("symbol", "qty", "side", "type", "time_in_force", "order_class").map { oco.text(it) })
            .containsExactly("MSFT", "2", "sell", "limit", "gtc", "oco")
        assertThat(oco.text("client_order_id")).isEqualTo("${target.need("client_order_id")}~r1~o$stopLabel")
        assertThat(oco.obj("take_profit").text("limit_price")).isEqualTo("990")
        assertThat(oco.obj("stop_loss").text("stop_price")).isEqualTo("300")
        assertThat(oco.obj("stop_loss").containsKey("limit_price")).isFalse
        assertThat(http.sent.filter { it.method == "DELETE" }.map { it.path }).containsExactly("/v2/orders/$targetId")
        // The target's cancellation is the OCO carrying it on: hidden from the stream, once.
        val cancelled = Fixtures.obj("order-exit-target-cancelled.json").with("id" to targetId)
        assertThat(desk.pairs.hides("canceled", cancelled)).isTrue
        assertThat(desk.pairs.hides("canceled", cancelled)).isFalse
    }

    @Test
    fun `a stop-limit keeps its limit on the oco's stop leg`() {
        val (desk, http) = desk(routes("order-exit-oco.json"))
        desk.place(stop(type = OrderType.STOP_LIMIT))
        assertThat(DeskFixture.body(http).obj("stop_loss").text("limit_price")).isEqualTo("299")
    }

    @Test
    fun `when alpaca refuses the oco the target is placed again as it was and the stop's refusal stands`() {
        val (desk, http) = desk(routes("422:error-oco-stop-above-target.json", "order-exit-target-restored.json"))
        assertThatThrownBy { desk.place(stop()) }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("insufficient qty")
        val again = DeskFixture.body(http)
        assertThat(listOf("symbol", "qty", "side", "type", "time_in_force", "limit_price").map { again.text(it) })
            .containsExactly("MSFT", "2", "sell", "limit", "gtc", "990")
        assertThat(again.text("client_order_id")).isEqualTo("${target.need("client_order_id")}~r1")
    }

    @Test
    fun `a target that filled before its cancel leaves the stop refused, its fill still reported`() {
        val filled = Fixtures.obj("order-exit-target-cancelled.json").with("status" to "filled").toString()
        val http = FixtureHttp(DeskFixture.assets + routes())
        val edited = EditedHttp(http, mapOf("GET /v2/orders/$targetId" to filled))
        val (desk, _) = DeskFixture.desk(http, clock = { placedAt }, alpaca = edited)
        assertThatThrownBy { desk.place(stop()) }.isInstanceOf(VenueRefusedException::class.java)
        assertThat(http.sent.count { it.method == "POST" }).isEqualTo(1)
        val cancelled = Fixtures.obj("order-exit-target-cancelled.json").with("id" to targetId)
        assertThat(desk.pairs.hides("canceled", cancelled)).isFalse
    }

    @Test
    fun `no oco is tried without a target placed in the last minute, or with labels too long for alpaca`() {
        val (late, lateHttp) = desk(routes(), clock = { placedAt + 61_000 })
        assertThatThrownBy { late.place(stop()) }.isInstanceOf(VenueRefusedException::class.java)
        assertThat(lateHttp.sent.none { it.method == "DELETE" }).isTrue
        val (long, longHttp) = desk(routes())
        assertThatThrownBy { long.place(stop(label = "s".repeat(110))) }.isInstanceOf(VenueRefusedException::class.java)
        assertThat(longHttp.sent.none { it.method == "DELETE" }).isTrue
    }

    @Test
    fun `only a share's stop refused for held shares looks for a target`() {
        val limit = stop().copy(type = OrderType.LIMIT, stopPrice = null)
        val (desk, http) = desk(routes())
        assertThatThrownBy { desk.place(limit) }.isInstanceOf(VenueRefusedException::class.java)
        val (power, powerHttp) = desk(mapOf("POST /v2/orders" to listOf("403:error-buying-power.json")))
        assertThatThrownBy { power.place(stop()) }.isInstanceOf(VenueRefusedException::class.java)
        val (bad, badHttp) = desk(mapOf("POST /v2/orders" to listOf("422:error-sub-penny.json")))
        assertThatThrownBy { bad.place(stop()) }.isInstanceOf(VenueRefusedException::class.java)
        assertThat((http.sent + powerHttp.sent + badHttp.sent).none { it.path == "/v2/orders" && it.method == "GET" })
            .isTrue
    }

    @Test
    fun `a coin takes no plain stop and is refused before alpaca is asked, a coin stop-limit goes out`() {
        val (desk, http) = desk(mapOf("POST /v2/orders" to listOf("order-crypto-limit.json")))
        assertThatThrownBy { desk.place(stop(symbol = "BTCUSD")) }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("only stop_limit")
        assertThat(http.sent.none { it.method == "POST" }).isTrue
        desk.place(stop(symbol = "BTCUSD", type = OrderType.STOP_LIMIT))
        assertThat(DeskFixture.body(http).text("type")).isEqualTo("stop_limit")
    }
}
