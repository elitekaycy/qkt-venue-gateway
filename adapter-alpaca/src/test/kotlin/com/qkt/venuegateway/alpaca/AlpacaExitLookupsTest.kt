package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.objects
import com.qkt.venuegateway.support.JsonReads.text
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An OCO of a target and its stop (recorded on paper, MSFT) is listed, looked up and replaced under both labels. */
class AlpacaExitLookupsTest {
    private val target = "dsl-pair-tp.mi1783a"
    private val stop = "dsl-pair-sl.mi1783a"
    private val leg = Fixtures.obj("order-exit-oco-leg.json")

    @Test
    fun `an open oco is listed as its target and its held stop leg, each under its own label`() {
        val (desk, _) = DeskFixture.desk(mapOf("GET /v2/orders" to listOf("orders-open-nested-oco.json")))
        val open = desk.open()
        assertThat(open.map { it.clientOrderId }).containsExactly(target, stop)
        assertThat(open.map { it.type }).containsExactly(OrderType.LIMIT, OrderType.STOP)
        assertThat(open.map { it.status }).containsOnly(OrderStatus.WORKING)
        // The leg's label is now known: its trade updates need no lookup.
        assertThat(desk.pairs.label(Fixtures.obj("order-exit-oco.json").objects("legs").single())).isEqualTo(stop)
    }

    @Test
    fun `a stop leg's label is read from its oco once, any other order keeps its own`() {
        val (desk, http) = DeskFixture.desk(mapOf("GET /v2/orders" to listOf("orders-all-nested-oco.json")))
        assertThat(desk.pairs.label(leg)).isEqualTo(stop)
        assertThat(desk.pairs.label(leg)).isEqualTo(stop)
        assertThat(http.sent.count { it.path == "/v2/orders" }).isEqualTo(1)
        assertThat(desk.pairs.label(Fixtures.obj("order-exit-target-cancelled.json"))).isEqualTo(target)
        val stranger = leg.with("id" to "other-leg", "group_id" to "other-group")
        assertThat(desk.pairs.label(stranger)).isEqualTo(leg.need("client_order_id"))
    }

    @Test
    fun `the stop is found by its label through its oco, and the cancelled target through the oco carrying it on`() {
        val (desk, _) =
            DeskFixture.desk(
                mapOf(
                    "GET /v2/orders:by_client_order_id" to
                        listOf("404:order-by-label-unknown.json", "order-exit-target-cancelled.json"),
                    "GET /v2/orders" to listOf("orders-all-nested-oco.json"),
                ),
            )
        val found = desk.byLabel(stop)!!
        assertThat(found.clientOrderId).isEqualTo(stop)
        assertThat(found.stopPrice).isEqualByComparingTo("310")
        val carried = desk.byLabel(target)!!
        assertThat(carried.clientOrderId).isEqualTo(target)
        assertThat(carried.status).isEqualTo(OrderStatus.WORKING)
    }

    @Test
    fun `a target cancelled by itself stays cancelled, and an unknown label is still unknown`() {
        val (desk, _) =
            DeskFixture.desk(
                mapOf(
                    "GET /v2/orders:by_client_order_id" to
                        listOf("order-exit-target-cancelled.json", "404:order-by-label-unknown.json"),
                    "GET /v2/orders" to listOf("orders-open-exit-target.json"),
                ),
            )
        assertThat(desk.byLabel(target)!!.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(desk.byLabel("never-placed")).isNull()
    }

    @Test
    fun `replacing the stop leg names it after the stop's label, the venue's own id dropped`() {
        val replaced = Fixtures.obj("order-exit-oco-leg-replace.json")
        val (desk, http) =
            DeskFixture.desk(
                mapOf(
                    "GET /v2/orders:by_client_order_id" to listOf("404:order-by-label-unknown.json"),
                    "GET /v2/orders" to listOf("orders-open-nested-oco.json", "orders-all-nested-oco.json"),
                    "PATCH /v2/orders/${leg.need("id")}" to listOf("order-exit-oco-leg-replace.json"),
                ),
            )
        assertThat(desk.modify(stop, OrderChange(stopPrice = BigDecimal("310")))).isTrue
        val sent = DeskFixture.body(http)
        assertThat(sent.text("client_order_id")).isEqualTo("$stop~r1")
        assertThat(sent.text("stop_price")).isEqualTo("310")
        assertThat(replaced.need("client_order_id")).isEqualTo("$stop~r1")
    }

    @Test
    fun `a fill of the stop leg is reported under the stop's label`() {
        val activities = Fixtures.array("activities-fill.json")
        val fill = activities.first()
        val unknown = listOf("404:order-by-label-unknown.json")
        val others = activities.drop(1).associate { "GET /v2/orders/${it.need("order_id")}" to unknown }
        val (desk, _) =
            DeskFixture.desk(
                others +
                    mapOf(
                        "GET /v2/account/activities/FILL" to listOf("activities-fill.json"),
                        "GET /v2/orders/${fill.need("order_id")}" to listOf("order-exit-oco-leg.json"),
                        "GET /v2/orders" to listOf("orders-all-nested-oco.json"),
                    ),
            )
        val labels = desk.fills(0L, 1_800_000_000_000L).filter { it.venueOrderId == fill.need("order_id") }
        assertThat(labels.map { it.clientOrderId }).containsOnly(stop)
    }
}
