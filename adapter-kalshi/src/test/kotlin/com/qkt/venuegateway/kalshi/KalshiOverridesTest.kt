package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.CURRY
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.RESTING
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The account's and the market's answers Kalshi recorded, one field altered per case, served through the venue. */
class KalshiOverridesTest {
    private val venue = KalshiVenue()
    private val adapter = venue.adapter()

    @AfterEach
    fun stop() {
        adapter.close()
        venue.close()
    }

    /** Recorded answer [name] with the first row of its [list] passed through [edit]. */
    private fun firstRow(
        name: String,
        list: String,
        edit: (JsonObject) -> JsonObject,
    ): String {
        val root = Fixtures.json(name)
        val rows = root[list]!!.jsonArray
        return JsonObject(root + (list to JsonArray(listOf(edit(rows.first().jsonObject)) + rows.drop(1)))).toString()
    }

    private fun JsonObject.with(
        key: String,
        value: JsonElement?,
    ) = JsonObject(if (value == null) this - key else this + (key to value))

    private fun limit(
        label: String,
        side: Side = Side.BUY,
        reduceOnly: Boolean = false,
    ) = NewOrder(
        label,
        CURRY,
        side,
        OrderType.LIMIT,
        BigDecimal.ONE,
        BigDecimal("0.01"),
        null,
        TimeInForce.GTC,
        reduceOnly,
    )

    @Test
    fun `a cancel kalshi refuses for any reason but the order being gone is raised`() {
        adapter.place(limit("qkt-probe-rest"))
        venue.overrides["DELETE /portfolio/events/orders/$RESTING"] = "!400 private/place-funds.json"

        assertThatThrownBy { adapter.cancel("qkt-probe-rest") }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("insufficient")
    }

    @Test
    fun `open orders are only those kalshi still rests, even when its answer holds ended ones`() {
        venue.overrides["GET /portfolio/orders"] = "private/orders-all.json"

        val open = adapter.openOrders()

        assertThat(open.map { it.clientOrderId }).containsExactly("qkt-probe-frac")
        assertThat(open.single().status).isEqualTo(OrderStatus.WORKING)
    }

    @Test
    fun `an order placed elsewhere, naming no client id, is listed under its kalshi id`() {
        venue.overrides["GET /portfolio/orders"] =
            firstRow("private/orders-resting.json", "orders") { it.with("client_order_id", null) }

        val open = adapter.openOrders().single()

        assertThat(open.clientOrderId).isEqualTo(RESTING)
        assertThat(open.venueOrderId).isEqualTo(RESTING)
    }

    @Test
    fun `a fill carrying its client id is read back under that label`() {
        venue.overrides["GET /portfolio/fills"] =
            firstRow("private/fills.json", "fills") { it.with("client_order_id", JsonPrimitive("qkt-elsewhere")) }

        val fills = adapter.fills(0, Long.MAX_VALUE)

        assertThat(fills.map { it.clientOrderId }).contains("qkt-elsewhere")
    }

    @Test
    fun `a resting reduce-only buy against a held short is sent for at most the short`() {
        venue.overrides["GET /portfolio/positions"] =
            Fixtures.json("private/positions.json").let { root ->
                val rows = root["market_positions"]!!.jsonArray.map { it.jsonObject }
                val held = rows.map { it["ticker"].toString().contains(CURRY) }
                val short =
                    rows.zip(held).map { (row, curry) ->
                        if (curry) row.with("position_fp", JsonPrimitive("-2.00")) else row
                    }
                JsonObject(root + ("market_positions" to JsonArray(short))).toString()
            }

        val placed = adapter.place(limit("cover", reduceOnly = true).copy(quantity = BigDecimal("5")))

        val body =
            venue.server.requests
                .last { it.method == "POST" }
                .body!!
                .utf8()
        assertThat(body).contains("\"count\":\"2\"").contains("\"side\":\"bid\"")
        assertThat(placed.status).isEqualTo(OrderStatus.WORKING)
    }

    @Test
    fun `a market kalshi no longer answers, or that names no open interest, records no present figure`() {
        val now = System.currentTimeMillis()
        venue.overrides["GET /markets/$CURRY"] = "!404 private/error-not-found.json"
        val gone = adapter.openInterest(CURRY, now - 60_000, now + 60_000)
        venue.overrides["GET /markets/$CURRY"] =
            Fixtures.json("demo/market-$CURRY.json").let { root ->
                JsonObject(root + ("market" to root["market"]!!.jsonObject.with("open_interest_fp", null))).toString()
            }
        val blank = adapter.openInterest(CURRY, now - 60_000, now + 60_000)

        assertThat(gone).isEmpty()
        assertThat(blank).isEmpty()
    }

    @Test
    fun `candles kalshi answers outside the range asked give no bar`() {
        val candles = Fixtures.json("demo/candles-1m-$CURRY.json")
        val priced =
            candles["candlesticks"]!!.jsonArray.map { c ->
                val o = c.jsonObject

                fun side(price: String) =
                    JsonObject(
                        listOf("open_dollars", "high_dollars", "low_dollars", "close_dollars").associateWith {
                            JsonPrimitive(price)
                        },
                    )
                o.with("yes_bid", side("0.0300")).with("yes_ask", side("0.0500"))
            }
        venue.overrides["GET /series/KXNBA3D/markets/$CURRY/candlesticks"] =
            JsonObject(candles + ("candlesticks" to JsonArray(priced))).toString()
        val starts = priced.map { it.jsonObject["end_period_ts"].toString().toLong() * 1000 - 60_000 }.sorted()

        val all = adapter.klines(CURRY, 60_000, starts.first(), starts.last() + 60_000)
        val one = adapter.klines(CURRY, 60_000, starts[1], starts[1] + 60_000)

        assertThat(all.map { it.startMs }).containsExactlyElementsOf(starts)
        assertThat(one.map { it.startMs }).containsExactly(starts[1])
        assertThat(one.single().close).isEqualByComparingTo("0.04")
    }
}
