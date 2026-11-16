package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.alpaca.client.AlpacaHttp
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import com.qkt.venuegateway.support.JsonReads.instantMs
import java.io.IOException
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** What the adapter does with an answer it cannot trust: Alpaca unreachable, or a recorded answer missing a field. */
class AlpacaGuardsTest {
    private fun JsonObject.with(
        key: String,
        value: String,
    ) = JsonObject(this + (key to JsonPrimitive(value)))

    private fun JsonObject.without(key: String) = JsonObject(this - key)

    @Test
    fun `an order Alpaca never answered is unavailable, so the host resolves it by its label`() {
        val assets = FixtureHttp(mapOf("GET /v2/assets/AAPL" to listOf("asset-AAPL.json")))
        val unreachable =
            object : AlpacaHttp by assets {
                override fun send(
                    method: String,
                    path: String,
                    body: String,
                ): String = throw IOException("connection reset")
            }
        val trading = AlpacaTrading(unreachable)
        val settings = AlpacaSettings(AlpacaEnvironment.PAPER)
        val listing = AlpacaListing(trading, setOf(AlpacaSettings.EQUITY), { 0L })
        val desk = AlpacaOrderDesk(trading, listing, settings, { BigDecimal.ZERO }, { 0L }, {})
        val price = BigDecimal("1.00")
        val order = NewOrder("qkt-1", "AAPL", Side.BUY, OrderType.LIMIT, BigDecimal.ONE, price, null, TimeInForce.DAY)
        assertThatThrownBy { desk.place(order) }
            .isInstanceOf(VenueUnavailableException::class.java)
            .hasMessageContaining("connection reset")
    }

    @Test
    fun `an asset of a class the adapter does not trade fails naming the class`() {
        val option = Fixtures.obj("asset-AAPL.json").with("class", "us_option")
        assertThatThrownBy { AlpacaInstruments.instrument(option) }.hasMessageContaining("us_option")
    }

    @Test
    fun `a side qkt cannot hold fails naming it`() {
        assertThatThrownBy { AlpacaMapping.side("buy_to_cover") }.hasMessageContaining("buy_to_cover")
    }

    @Test
    fun `an order without created_at fails, and one never updated is stamped when it was created`() {
        val order = Fixtures.obj("order-limit-accepted.json")
        assertThatThrownBy { AlpacaMapping.order(order.without("created_at")) }.hasMessageContaining("created_at")
        val never = AlpacaMapping.order(order.without("updated_at"))
        assertThat(never.updatedAtMs).isEqualTo(order.instantMs("created_at"))
    }

    @Test
    fun `a fill without its time fails naming the field, from the stream or from the activities`() {
        val update =
            Fixtures.updates("ws-trade-updates-fill.json").first { (it["event"] as JsonPrimitive).content == "fill" }
        assertThatThrownBy { AlpacaMapping.fill(update.without("timestamp")) }.hasMessageContaining("timestamp")
        val activity = Fixtures.array("activities-fill.json").first()
        assertThatThrownBy { AlpacaMapping.fill(activity.without("transaction_time"), "qkt-1") }
            .hasMessageContaining("transaction_time")
    }

    @Test
    fun `an account without margin uses none, and a quote without a price on a side has none there`() {
        val account = AlpacaMapping.account(Fixtures.obj("account.json").without("initial_margin"))
        assertThat(account.initialMargin).isEqualByComparingTo("0")
        assertThat(account.marginAvailable).isEqualByComparingTo(account.equity)
        val message = Fixtures.quotes("ws-data-crypto.json").first()
        val quote = AlpacaMapping.quote(message.with("bp", "0"))
        assertThat(quote.bid).isNull()
        assertThat(quote.ask).isNotNull
        assertThatThrownBy { AlpacaMapping.quote(message.without("t")) }.hasMessageContaining("without t")
    }
}
