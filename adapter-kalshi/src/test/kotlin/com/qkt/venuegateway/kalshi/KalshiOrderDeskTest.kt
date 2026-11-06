package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.CURRY
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.RESTING
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KalshiOrderDeskTest {
    private val venue = KalshiVenue()
    private val adapter = venue.adapter()

    @AfterEach
    fun stop() {
        adapter.close()
        venue.close()
    }

    private fun order(
        label: String,
        side: Side = Side.BUY,
        type: OrderType = OrderType.LIMIT,
        quantity: String = "1",
        tif: TimeInForce = TimeInForce.GTC,
        reduceOnly: Boolean = false,
        symbol: String = CURRY,
    ) = NewOrder(label, symbol, side, type, BigDecimal(quantity), BigDecimal("0.01"), null, tif, reduceOnly)

    private fun posted(): String {
        val post = venue.server.requests.last { it.method == "POST" }
        return post.body!!.utf8()
    }

    private fun lastPlacement() =
        Json
            .parseToJsonElement(posted())
            .jsonObject
            .mapValues { it.value.jsonPrimitive.content }

    @Test
    fun `a placed order is read back by its id, working under its label`() {
        val placed = adapter.place(order("qkt-probe-rest"))

        assertThat(placed.clientOrderId).isEqualTo("qkt-probe-rest")
        assertThat(placed.venueOrderId).isEqualTo(RESTING)
        assertThat(placed.status).isEqualTo(OrderStatus.WORKING)
        assertThat(lastPlacement()["client_order_id"]).isEqualTo("qkt-probe-rest")
        assertThat(adapter.orderByLabel("qkt-probe-rest")!!.venueOrderId).isEqualTo(RESTING)
    }

    @Test
    fun `a label Kalshi already holds answers the order holding it, one it cannot find stays refused`() {
        val retried = adapter.place(order("qkt-probe-fill"))

        assertThat(retried.status).isEqualTo(OrderStatus.FILLED)
        assertThatThrownBy { adapter.place(order("ghost")) }.hasMessageContaining("409 order_already_exists")
        assertThatThrownBy { adapter.place(order("funds")) }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("insufficient_balance")
    }

    @Test
    fun `an order Kalshi took but does not show is unavailable, for the host to resolve by label`() {
        assertThatThrownBy { adapter.place(order("lost")) }.isInstanceOf(VenueUnavailableException::class.java)
    }

    @Test
    fun `an unlisted market and a market of another shard are refused before Kalshi`() {
        assertThatThrownBy { adapter.place(order("x", symbol = "NOPE")) }
            .hasMessageContaining("kalshi lists no market NOPE")
        venue.adapter(shard = 3).use { other ->
            assertThatThrownBy { other.place(order("x")) }.hasMessageContaining("not traded by this gateway")
        }
        assertThat(venue.server.requests.none { it.method == "POST" }).isTrue
    }

    @Test
    fun `a resting reduce-only order is sent for at most the position, and refused with nothing to reduce`() {
        adapter.place(order("reduce", Side.SELL, quantity = "5", reduceOnly = true))

        assertThat(lastPlacement()["count"]).isEqualTo("2")
        assertThat(lastPlacement()).doesNotContainKey("reduce_only")
        assertThatThrownBy { adapter.place(order("grow", Side.BUY, reduceOnly = true)) }
            .hasMessageContaining("no position to reduce")
        adapter.place(order("close", Side.SELL, OrderType.MARKET, quantity = "2", reduceOnly = true))
        assertThat(lastPlacement()["reduce_only"]).isEqualTo("true")
        assertThat(lastPlacement()["time_in_force"]).isEqualTo("immediate_or_cancel")
        assertThat(lastPlacement()["price"]).isEqualTo("0.01")
    }

    @Test
    fun `a cancel reaches only a resting order, an ended or unknown one is no error`() {
        adapter.place(order("qkt-probe-rest"))
        adapter.cancel("qkt-probe-rest")
        adapter.cancel("qkt-probe-fill")
        adapter.cancel("ghost")
        adapter.cancel("qkt-probe-frac")

        val deleted = venue.server.requests.filter { it.method == "DELETE" }
        val deletes = deleted.map { it.url.encodedPath.substringAfterLast('/') }
        assertThat(deletes).containsExactly(RESTING, "01a1ae62-0d80-7507-ad66-861803c29ad9")
    }

    @Test
    fun `a change amends the total quantity and the price, an unknown label is false and a stop is refused`() {
        assertThat(adapter.modify("qkt-probe-rest", OrderChange(quantity = BigDecimal("3")))).isTrue

        val amended = venue.server.requests.last()
        val amend = Json.parseToJsonElement(amended.body!!.utf8()).jsonObject
        assertThat(amend["count"]!!.jsonPrimitive.content).isEqualTo("3")
        assertThat(amend["price"]!!.jsonPrimitive.content).isEqualTo("0.0200")
        assertThat(adapter.modify("ghost", OrderChange(limitPrice = BigDecimal("0.03")))).isFalse
        assertThatThrownBy { adapter.modify("qkt-probe-rest", OrderChange(stopPrice = BigDecimal("0.5"))) }
            .hasMessageContaining("no stop orders")
    }

    @Test
    fun `open orders are those Kalshi rests`() {
        val open = adapter.openOrders()

        assertThat(open.single().clientOrderId).isEqualTo("qkt-probe-rest")
        assertThat(open.single().status).isEqualTo(OrderStatus.WORKING)
    }
}
