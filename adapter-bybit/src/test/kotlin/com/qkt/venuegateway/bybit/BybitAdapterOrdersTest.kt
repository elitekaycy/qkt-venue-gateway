package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BybitAdapterOrdersTest {
    @TempDir lateinit var dir: Path
    private val venues = mutableListOf<BybitVenue>()

    private fun venue(override: Map<String, String> = emptyMap()) =
        BybitVenue(dir, { path, query -> override[path] ?: BybitVenue.linearRoute(path, query) }).also {
            venues += it
        }

    private fun limit(symbol: String = "BTCUSDT") =
        NewOrder(
            "rec-rest-1",
            symbol,
            Side.BUY,
            OrderType.LIMIT,
            BigDecimal("0.001"),
            BigDecimal("77392.3"),
            null,
            TimeInForce.GTC,
        )

    private fun BybitVenue.body(path: String) = sent(path).single().body!!.utf8()

    @AfterEach
    fun stop() = venues.forEach { it.close() }

    @Test
    fun `a placed order is sent under its label with its one-way index, then read back as bybit holds it`() {
        val venue = venue()

        val placed = venue.adapter.place(limit())

        assertThat(placed.clientOrderId).isEqualTo("rec-rest-1")
        assertThat(placed.venueOrderId).isEqualTo("39bbc94a-2310-4324-9996-707881007fd5")
        assertThat(placed.status).isEqualTo(OrderStatus.WORKING)
        assertThat(venue.body("/v5/order/create"))
            .contains("\"orderLinkId\":\"rec-rest-1\"", "\"positionIdx\":0", "\"price\":\"77392.3\"")
        assertThat(venue.sent("/v5/position/list").single().url.queryParameter("symbol")).isEqualTo("BTCUSDT")
    }

    @Test
    fun `an order on a code the account does not list is refused before anything is sent`() {
        val venue = venue()

        assertThatThrownBy { venue.adapter.place(limit("NOPEUSDT")) }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("NOPEUSDT")
        assertThat(venue.sent("/v5/order/create")).isEmpty()
    }

    @Test
    fun `a position index bybit says does not match its mode makes the next order read the mode again`() {
        val venue = venue(mapOf("/v5/order/create" to "private/error-position-idx.json"))

        repeat(2) {
            assertThatThrownBy { venue.adapter.place(limit()) }
                .isInstanceOf(VenueRefusedException::class.java)
                .hasMessageContaining("position idx not match position mode")
        }
        assertThat(venue.sent("/v5/position/list")).hasSize(2)
    }

    @Test
    fun `any other refusal is raised as bybit worded it and the mode read stays held`() {
        val venue = venue(mapOf("/v5/order/create" to "private/error-qty-step.json"))

        repeat(2) {
            assertThatThrownBy { venue.adapter.place(limit()) }
                .isInstanceOf(VenueRefusedException::class.java)
                .hasMessage("bybit 10001: Qty invalid")
        }
        assertThat(venue.sent("/v5/position/list")).hasSize(1)
    }

    @Test
    fun `open orders are the working ones the account holds, read across its pages`() {
        val venue = venue()

        val open = venue.adapter.openOrders()

        assertThat(open.map { it.clientOrderId }).containsExactly("rec-rest-1")
        val reads = venue.sent("/v5/order/realtime")
        assertThat(reads.first().url.queryParameter("settleCoin")).isEqualTo("USDT")
        assertThat(reads.first().url.queryParameter("openOnly")).isEqualTo("0")
        assertThat(reads.last().url.queryParameter("cursor")).isNotBlank()
    }

    @Test
    fun `a working order is cancelled by its symbol and label`() {
        val venue = venue()

        venue.adapter.cancel("rec-rest-1")

        assertThat(venue.body("/v5/order/cancel"))
            .isEqualTo("""{"category":"linear","symbol":"BTCUSDT","orderLinkId":"rec-rest-1"}""")
    }

    @Test
    fun `cancelling an order bybit never had, or one that already ended, sends nothing`() {
        val unknown = venue()
        unknown.adapter.cancel("rec-unknown")
        assertThat(unknown.sent("/v5/order/history").single().url.queryParameter("orderLinkId"))
            .isEqualTo("rec-unknown")
        assertThat(unknown.sent("/v5/order/cancel")).isEmpty()

        val ended = venue(mapOf("/v5/order/realtime" to "private/order-limit-cancelled.json"))
        ended.adapter.cancel("rec-rest-1")
        assertThat(ended.sent("/v5/order/cancel")).isEmpty()
    }

    @Test
    fun `an order that ends between the read and the cancel is left as it ended`() {
        val venue = venue(mapOf("/v5/order/cancel" to "private/cancel-unknown.json"))

        venue.adapter.cancel("rec-rest-1")

        assertThat(venue.sent("/v5/order/cancel")).hasSize(1)
    }

    @Test
    fun `a cancel refused for any other reason is raised`() {
        val venue = venue(mapOf("/v5/order/cancel" to "private/error-unknown-symbol.json"))

        assertThatThrownBy { venue.adapter.cancel("rec-rest-1") }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("symbol invalid")
    }

    @Test
    fun `a change sends only the fields it changes, and a change to an unknown order is false`() {
        val venue = venue()

        val changed =
            venue.adapter.modify(
                "rec-rest-1",
                OrderChange(quantity = BigDecimal("0.002"), limitPrice = BigDecimal("77382.3")),
            )
        val unknown = venue.adapter.modify("rec-unknown", OrderChange(quantity = BigDecimal("0.002")))

        assertThat(changed).isTrue
        assertThat(unknown).isFalse
        assertThat(venue.body("/v5/order/amend")).isEqualTo(
            """{"category":"linear","symbol":"BTCUSDT","orderLinkId":"rec-rest-1","qty":"0.002","price":"77382.3"}""",
        )
    }

    @Test
    fun `a stop change sends its trigger price`() {
        val venue = venue()

        venue.adapter.modify("rec-rest-1", OrderChange(stopPrice = BigDecimal("2739.61")))

        assertThat(venue.body("/v5/order/amend")).endsWith(""""orderLinkId":"rec-rest-1","triggerPrice":"2739.61"}""")
    }

    @Test
    fun `an order no longer live is found in the order history, and one in neither is unknown`() {
        val venue = venue(mapOf("/v5/order/history" to "private/order-history-limit-cancelled.json"))

        val ended = venue.adapter.orderByLabel("rec-rest-1")

        assertThat(ended!!.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(venue(mapOf()).adapter.orderByLabel("rec-unknown")).isNull()
    }
}
