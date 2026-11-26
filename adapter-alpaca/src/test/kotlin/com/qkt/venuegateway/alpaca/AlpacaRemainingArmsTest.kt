package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.alpaca.client.AlpacaData
import com.qkt.venuegateway.alpaca.client.AlpacaHttpException
import com.qkt.venuegateway.alpaca.client.AlpacaQuoteStream
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import com.qkt.venuegateway.support.JsonReads.text
import java.math.BigDecimal
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AlpacaRemainingArmsTest {
    private val unknown = "404:order-by-label-unknown.json"

    @Test
    fun `a cancel waits through a poll that finds no order and one still working until the order is final`() {
        val working = Fixtures.obj("order-by-label-working.json")
        val (desk, http) =
            DeskFixture.desk(
                mapOf(
                    "GET /v2/orders:by_client_order_id" to
                        listOf(
                            "order-by-label-working.json",
                            "order-by-label-working.json",
                            unknown,
                            "order-by-label-cancelled.json",
                        ),
                    "DELETE /v2/orders/${working.text("id")}" to listOf(""),
                    "GET /v2/orders" to listOf("orders-open.json"),
                ),
            )

        desk.cancel(working.text("client_order_id")!!)

        assertThat(http.sent.count { it.path == "/v2/orders:by_client_order_id" }).isEqualTo(4)
        assertThat(desk.byLabel(working.text("client_order_id")!!)?.status).isEqualTo(OrderStatus.CANCELLED)
    }

    @Test
    fun `a replace waits through a poll that finds no order and one that is not yet the replacement`() {
        val original = Fixtures.obj("order-by-label-after-replace.json")
        val replacement = Fixtures.obj("order-crypto-replaced.json")
        val (desk, http) =
            DeskFixture.desk(
                mapOf(
                    "GET /v2/orders:by_client_order_id" to
                        listOf(
                            "order-crypto-limit.json",
                            unknown,
                            "order-crypto-limit.json",
                            "order-by-label-after-replace.json",
                        ),
                    "PATCH /v2/orders/${original.text("id")}" to listOf("order-crypto-replaced.json"),
                    "GET /v2/orders/${replacement.text("id")}" to listOf("order-crypto-replaced.json"),
                    "GET /v2/orders" to listOf("orders-open.json"),
                ),
            )

        assertThat(
            desk.modify(original.text("client_order_id")!!, OrderChange(limitPrice = BigDecimal("60100"))),
        ).isTrue
        assertThat(http.sent.count { it.path == "/v2/orders:by_client_order_id" }).isEqualTo(4)
    }

    @Test
    fun `a replaced order whose successor cannot be read, or is not named, is read as it stands`() {
        val replaced = Fixtures.obj("order-by-label-after-replace.json")
        val label = replaced.text("client_order_id")!!
        val routes = mapOf("GET /v2/orders:by_client_order_id" to listOf("order-by-label-after-replace.json"))
        val (gone, _) = DeskFixture.desk(routes + ("GET /v2/orders/${replaced.text("replaced_by")}" to listOf(unknown)))
        val unnamed = JsonObject(replaced + ("replaced_by" to JsonNull)).toString()
        val trading =
            AlpacaTrading(EditedHttp(FixtureHttp(routes), mapOf("GET /v2/orders:by_client_order_id" to unnamed)))
        val listing = AlpacaListing(trading, setOf(AlpacaSettings.CRYPTO), { 0L })
        val desk =
            AlpacaOrderDesk(trading, listing, AlpacaSettings(AlpacaEnvironment.PAPER), { BigDecimal.ZERO }, { 0L }, {})

        assertThat(gone.byLabel(label)?.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(desk.byLabel(label)?.venueOrderId).isEqualTo(replaced.text("id"))
    }

    @Test
    fun `a fill of an order Alpaca no longer finds is labelled by its order id, and a found one by its label`() {
        val known = "7f36ea40-5eb9-4a72-a552-f39a6275fa15"
        val lost = "1061e360-3463-4c36-b29a-daf9ae3107f9"
        val (desk, _) =
            DeskFixture.desk(
                mapOf(
                    "GET /v2/account/activities/FILL" to listOf("activities-fill.json"),
                    "GET /v2/orders/$known" to listOf("order-crypto-filled.json"),
                    "GET /v2/orders/$lost" to listOf(unknown),
                ),
            )

        val labels = desk.fills(0, 1).associate { it.venueOrderId to it.clientOrderId }

        assertThat(labels[known]).isEqualTo("qkt-fill-1793144310")
        assertThat(labels[lost]).isEqualTo(lost)
    }

    @Test
    fun `a lookup Alpaca fails for any reason but not found is raised, not read as missing`() {
        val trading = AlpacaTrading(FixtureHttp(mapOf("GET /v2/orders/x" to listOf("500:error-buying-power.json"))))

        assertThatThrownBy { trading.order("x") }
            .isInstanceOf(AlpacaHttpException::class.java)
            .extracting { (it as AlpacaHttpException).status }
            .isEqualTo(500)
    }

    @Test
    fun `a share looked up that is a coin, or no longer active, is not listed`() {
        val aapl = Fixtures.obj("asset-AAPL.json")
        val coin = Fixtures.array("assets-crypto.json").first()
        val http =
            EditedHttp(
                FixtureHttp(emptyMap()),
                mapOf(
                    "GET /v2/assets/AAPL" to JsonObject(aapl + ("status" to JsonPrimitive("inactive"))).toString(),
                    "GET /v2/assets/BTCUSD" to coin.toString(),
                ),
            )
        val listing = AlpacaListing(AlpacaTrading(http), setOf(AlpacaSettings.EQUITY), { 0L })

        assertThat(listing.find("AAPL")).isNull()
        assertThat(listing.find("BTCUSD")).isNull()
    }

    @Test
    fun `bars answered as null, or not as a list for the symbol, are none`() {
        val page = Fixtures.obj("bars-stocks-1hour-last.json")
        val nulled = JsonObject(page + ("bars" to JsonNull)).toString()
        val odd = JsonObject(page + ("bars" to JsonObject(mapOf("AAPL" to JsonPrimitive("x"))))).toString()

        fun bars(text: String) =
            AlpacaData(EditedHttp(FixtureHttp(emptyMap()), mapOf("GET /v2/stocks/bars" to text)), "iex")
                .bars("AAPL", false, "1Hour", 0, 3_600_000)

        assertThat(bars(nulled)).isEmpty()
        assertThat(bars(odd)).isEmpty()
    }

    @Test
    fun `closing the quotes closes every feed opened and skips those never opened`() {
        val routes = mapOf("GET /v2/assets" to listOf("assets-crypto.json"))
        val listing = AlpacaListing(AlpacaTrading(FixtureHttp(routes)), setOf("crypto"), { 0L })
        val closed = mutableListOf<String>()
        val quotes =
            AlpacaQuotes(listing, false) { feed, symbols ->
                AlpacaQuoteStream("k", "s", symbols, {}, { _, _ -> }) to
                    object : AlpacaQuoteSocket {
                        override fun start() {}

                        override fun send(frame: String) = true

                        override fun close() {
                            closed += feed
                        }
                    }
            }
        quotes.subscribe(setOf("BTCUSD"))

        quotes.close()

        assertThat(closed).containsExactly("crypto")
    }
}
