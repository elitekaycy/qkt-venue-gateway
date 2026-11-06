package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueUnsupportedException
import com.qkt.venuegateway.support.JsonReads.text
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The adapter over recorded answers and replayed trade updates: what it pushes, reads back and sends. */
class AlpacaAdapterTest {
    private val routes =
        mapOf(
            "GET /v2/assets" to listOf("assets-crypto.json"),
            "GET /v2/positions" to listOf("positions-empty.json"),
            "GET /v1beta3/crypto/us/bars" to listOf("bars-crypto-1min-last.json"),
        )

    private fun wired(extra: Map<String, List<String>> = emptyMap()) = WiredAdapter(routes + extra)

    @Test
    fun `nothing is pushed before connect, and connect starts the trade stream that close stops`() {
        val w = wired()
        w.update(Fixtures.updates("ws-trade-updates-fill.json").first())
        w.link(false, "down")
        w.quote(AlpacaMapping.quote(Fixtures.quotes("ws-data-crypto.json").first()))
        w.feed(false, "down")
        val heard = HeardListener()
        w.adapter.connect(heard)
        assertThat(w.started).isEqualTo(1)
        assertThat(listOf(heard.orders, heard.fills, heard.quotes, heard.links, heard.feed)).allSatisfy {
            assertThat(it).isEmpty()
        }
        w.adapter.close()
        assertThat(w.closed).isEqualTo(1)
    }

    @Test
    fun `quotes and the quote feed's state reach the listener once connected`() {
        val w = wired()
        val heard = HeardListener().also(w.adapter::connect)
        val quote = AlpacaMapping.quote(Fixtures.quotes("ws-data-crypto.json").first())
        w.quote(quote)
        w.feed(true, "alpaca data stream subscribed")
        assertThat(heard.quotes).containsExactly(quote)
        assertThat(heard.feed).containsExactly(true to "alpaca data stream subscribed")
    }

    @Test
    fun `the stream coming up rereads positions before telling the listener, and a failed read still tells it`() {
        val w = wired()
        val heard = HeardListener().also(w.adapter::connect)
        w.link(true, "up")
        assertThat(w.http.sent.count { it.path == "/v2/positions" }).isEqualTo(1)
        val failing = wired(mapOf("GET /v2/positions" to listOf("500:error-buying-power.json")))
        val told = HeardListener().also(failing.adapter::connect)
        failing.link(true, "up")
        failing.link(false, "closed")
        assertThat(heard.links).containsExactly(true to "up")
        assertThat(told.links).containsExactly(true to "up", false to "closed")
    }

    @Test
    fun `a coin buy's fill carries the coin Alpaca kept as its fee, and a coin sale is reported as filled`() {
        val w = wired()
        val heard = HeardListener().also(w.adapter::connect)
        w.link(true, "up")
        Fixtures.updates("ws-trade-updates-coin-roundtrip.json").forEach(w.update)
        assertThat(heard.orders.last().status).isEqualTo(OrderStatus.FILLED)
        assertThat(heard.orders).hasSize(6)
        val (buy, sell) = heard.fills
        assertThat(buy.side).isEqualTo(Side.BUY)
        assertThat(buy.quantity).isEqualByComparingTo("0.0002")
        assertThat(buy.costs.single().kind).isEqualTo(CostKind.COMMISSION)
        assertThat(buy.costs.single().amount).isEqualByComparingTo("0.0000005")
        assertThat(buy.costs.single().currency).isEqualTo("BTC")
        assertThat(sell.side).isEqualTo(Side.SELL)
        assertThat(sell.costs).isEmpty()
    }

    @Test
    fun `an update the mapping cannot read is dropped without reaching the listener`() {
        val w = wired()
        val heard = HeardListener().also(w.adapter::connect)
        w.update(Fixtures.obj("account.json"))
        assertThat(heard.orders).isEmpty()
        assertThat(heard.fills).isEmpty()
    }

    @Test
    fun `fills read back are the activities under their labels, the streamed copy winning for the same execution`() {
        val activities = Fixtures.array("activities-fill.json")
        val filled = Fixtures.obj("order-crypto-filled.json")
        val unknown = activities.last().text("order_id")
        val w =
            wired(
                mapOf(
                    "GET /v2/account/activities/FILL" to listOf("activities-fill.json"),
                    "GET /v2/orders/${filled.text("id")}" to listOf("order-crypto-filled.json"),
                    "GET /v2/orders/$unknown" to listOf("404:order-by-label-unknown.json"),
                ),
            )
        HeardListener().also(w.adapter::connect)
        w.link(true, "up")
        Fixtures.updates("ws-trade-updates-fill.json").forEach(w.update)
        val fills = w.adapter.fills(0L, 1_800_000_000_000L)
        assertThat(fills).hasSize(3)
        assertThat(fills.take(2).map { it.clientOrderId }).containsOnly(filled.text("client_order_id"))
        assertThat(fills.take(2)).allSatisfy { assertThat(it.costs.single().currency).isEqualTo("BTC") }
        // An order Alpaca no longer finds keeps its fill, under the order's own id.
        assertThat(fills.last().clientOrderId).isEqualTo(unknown)
        assertThat(fills.last().costs).isEmpty()
        // The order of a fill is looked up once, however many fills it had.
        assertThat(w.http.sent.count { it.path == "/v2/orders/${filled.text("id")}" }).isEqualTo(1)
    }

    @Test
    fun `klines are the venue's bars inside the window, and an empty window asks nothing`() {
        val w = wired()
        val bars = Fixtures.obj("bars-crypto-1min-last.json")["bars"].toString()
        val first = Instant.parse(Regex(""""t":"([^"]+)"""").find(bars)!!.groupValues[1]).toEpochMilli()
        val all = w.adapter.klines("BTCUSD", 60_000, first, first + 3_600_000)
        assertThat(all).hasSize(9)
        assertThat(all.first().startMs).isEqualTo(first)
        val trimmed = w.adapter.klines("BTCUSD", 60_000, first + 60_000, first + 120_000)
        assertThat(trimmed.map { it.startMs }).containsExactly(first + 60_000)
        val asked = w.http.sent.count { it.path == "/v1beta3/crypto/us/bars" }
        assertThat(w.adapter.klines("BTCUSD", 60_000, first, first)).isEmpty()
        assertThat(w.http.sent.count { it.path == "/v1beta3/crypto/us/bars" }).isEqualTo(asked)
    }

    @Test
    fun `settlements are not served and quotes subscribe through the quote feed`() {
        val w = wired()
        assertThatThrownBy { w.adapter.settlements(0L, 1L) }.isInstanceOf(VenueUnsupportedException::class.java)
        w.adapter.subscribeQuotes(emptySet())
        assertThat(w.http.sent).isEmpty()
    }

    @Test
    fun `orders go through the desk, a reduce-only close sized by the held position`() {
        val working = Fixtures.obj("order-by-label-working.json")
        val original = Fixtures.obj("order-by-label-after-replace.json")
        val replacement = Fixtures.obj("order-crypto-replaced.json")
        val w =
            wired(
                mapOf(
                    "GET /v2/positions" to listOf("positions-crypto.json"),
                    "POST /v2/orders" to listOf("order-crypto-market.json"),
                    "GET /v2/orders:by_client_order_id" to
                        listOf(
                            "order-by-label-working.json",
                            "order-by-label-cancelled.json",
                            "order-crypto-limit.json",
                            "order-by-label-after-replace.json",
                        ),
                    "DELETE /v2/orders/${working.text("id")}" to listOf(""),
                    "PATCH /v2/orders/${original.text("id")}" to listOf("order-crypto-replaced.json"),
                    "GET /v2/orders/${replacement.text("id")}" to listOf("order-crypto-replaced.json"),
                ),
            )
        val close =
            NewOrder("qkt-c", "BTCUSD", Side.SELL, OrderType.MARKET, BigDecimal.ONE, null, null, TimeInForce.GTC, true)
        w.adapter.place(close)
        assertThat(DeskFixture.body(w.http).text("qty")).isEqualTo("0.000199499")
        w.adapter.cancel(working.text("client_order_id")!!)
        assertThat(w.http.sent.count { it.method == "DELETE" }).isEqualTo(1)
        val label = original.text("client_order_id")!!
        assertThat(w.adapter.modify(label, OrderChange(limitPrice = BigDecimal("60100")))).isTrue
        assertThat(w.adapter.orderByLabel(label)!!.venueOrderId).isEqualTo(replacement.text("id"))
    }
}
