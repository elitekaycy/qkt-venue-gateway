package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.kalshi.Fixtures
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KalshiPortfolioTest {
    private val placed = "01a1ae61-bf60-7752-859e-2842fa23b0ba"
    private val server =
        Fixtures.Server { method, path, query, _ ->
            when ("$method $path") {
                "GET /portfolio/balance" -> "private/balance.json"
                "GET /portfolio/positions" -> "private/positions.json"
                "GET /portfolio/orders" ->
                    if (query["status"] == "resting") "private/orders-resting.json" else "private/orders-all.json"
                "GET /portfolio/orders/$placed" -> "private/order-amended.json"
                "GET /portfolio/fills" -> "private/fills.json"
                "GET /portfolio/settlements" -> "private/settlements-empty.json"
                "POST /portfolio/events/orders" -> "!201 private/place-resting.json"
                "POST /portfolio/events/orders/$placed/amend" -> "private/amend.json"
                "DELETE /portfolio/events/orders/$placed" -> "private/cancel.json"
                "DELETE /portfolio/events/orders/gone" -> "!400 private/place-funds.json"
                else -> null
            }
        }
    private val portfolio = KalshiPortfolio(KalshiRest(server.restUrl, KalshiAuth("key-id", Fixtures.pem), { 42L }))

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `every call is signed with the key id, the time and the full path`() {
        portfolio.balance()

        val request = server.requests.single()
        assertThat(request.headers["KALSHI-ACCESS-KEY"]).isEqualTo("key-id")
        assertThat(request.headers["KALSHI-ACCESS-TIMESTAMP"]).isEqualTo("42")
        assertThat(request.headers["KALSHI-ACCESS-SIGNATURE"].isNullOrBlank()).describedAs("signed").isFalse
    }

    @Test
    fun `the balance is per shard in dollars, and the portfolio value in cents`() {
        val b = portfolio.balance()

        assertThat(b.byShard[0]).isEqualByComparingTo("999.9146")
        assertThat(b.byShard[3]).isEqualByComparingTo("0")
        assertThat(b.portfolioValueCents).isEqualTo(8L)
    }

    @Test
    fun `positions are signed yes counts with their cost, only those held`() {
        val p = portfolio.positions().associateBy { it.ticker }

        assertThat(p.getValue("KXAAAGASD-26NOV06-4.0600").position).isEqualByComparingTo("-1")
        assertThat(p.getValue("KXAAAGASD-26NOV06-4.0600").exposure).isEqualByComparingTo("0.9")
        assertThat(p.getValue("KXNBA3D-26NOV05MIAGSW-GSWSCURRY30").position).isEqualByComparingTo("2")
        assertThat(p.getValue("KXNBA3D-26NOV05MIAGSW-GSWSCURRY30").exchangeIndex).isEqualTo(0)
        assertThat(server.requests.single().url.queryParameter("count_filter")).isEqualTo("position")
    }

    @Test
    fun `orders are read by status or all, and one by its id`() {
        assertThat(portfolio.orders("resting").single().clientOrderId).isEqualTo("qkt-probe-rest")
        assertThat(portfolio.orders().map { it.status }).contains("resting", "canceled", "executed")
        val o = portfolio.order(placed)!!
        assertThat(o.yesPrice).isEqualByComparingTo("0.02")
        assertThat(o.remainingCount).isEqualByComparingTo("2")
        assertThat(o.initialCount).isEqualByComparingTo("1")
        assertThat(o.updatedMs).isEqualTo(1_793_927_009_415L)
        assertThat(portfolio.order("unknown")).isNull()
    }

    @Test
    fun `fills and settlements are read over a range in whole seconds`() {
        val fills = portfolio.fills(1_793_927_000_500L, 1_793_927_100_000L)

        assertThat(fills.map { it.bookSide }).contains("bid", "ask")
        assertThat(fills.first().fee).isEqualByComparingTo("0.0007")
        assertThat(fills.first().clientOrderId).isNull()
        assertThat(server.requests.single().url.queryParameter("min_ts")).isEqualTo("1793927000")
        assertThat(portfolio.settlements(0L, 1_000L)).isEmpty()
    }

    @Test
    fun `an order is sent in v2 words and its id read back`() {
        val sent =
            KalshiNewOrder(
                "KXNBA3D-26NOV05MIAGSW-GSWSCURRY30",
                "bid",
                BigDecimal("1"),
                BigDecimal("0.01"),
                "immediate_or_cancel",
                "qkt-probe-rest",
                true,
            )

        val answer = portfolio.place(sent)

        assertThat(answer.orderId).isEqualTo(placed)
        assertThat(answer.remainingCount).isEqualByComparingTo("1")
        val body = Json.parseToJsonElement(server.requests.single().body!!.utf8()).jsonObject
        assertThat(body.mapValues { it.value.jsonPrimitive.content }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "ticker" to "KXNBA3D-26NOV05MIAGSW-GSWSCURRY30",
                "side" to "bid",
                "count" to "1",
                "price" to "0.01",
                "time_in_force" to "immediate_or_cancel",
                "self_trade_prevention_type" to "taker_at_cross",
                "client_order_id" to "qkt-probe-rest",
                "reduce_only" to "true",
            ),
        )
    }

    @Test
    fun `an amend keeps the label, and a cancel is by id; a refusal carries Kalshi's code`() {
        val order = portfolio.order(placed)!!
        portfolio.amend(order, BigDecimal("3"), BigDecimal("0.03"))
        portfolio.cancel(placed)

        val amend = Json.parseToJsonElement(server.requests[1].body!!.utf8()).jsonObject
        assertThat(amend["count"]!!.jsonPrimitive.content).isEqualTo("3")
        assertThat(amend["updated_client_order_id"]!!.jsonPrimitive.content).isEqualTo("qkt-probe-rest")
        assertThat(server.requests[2].method).isEqualTo("DELETE")
        assertThatThrownBy { portfolio.cancel("gone") }
            .isInstanceOf(KalshiException::class.java)
            .hasMessageContaining("400 insufficient_balance")
    }
}
