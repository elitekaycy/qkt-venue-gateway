package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.Tradability
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AlpacaMappingArmsTest {
    private val limit = Fixtures.obj("order-limit-accepted.json")

    @Test
    fun `an order is reduce-only only when its position intent closes`() {
        assertThat(AlpacaMapping.order(limit.with("position_intent" to "sell_to_close")).reduceOnly).isTrue
        assertThat(AlpacaMapping.order(limit.with("position_intent" to "buy_to_close")).reduceOnly).isTrue
        assertThat(AlpacaMapping.order(limit.with("position_intent" to "buy_to_open")).reduceOnly).isFalse
        assertThat(AlpacaMapping.order(limit.without("position_intent")).reduceOnly).isFalse
    }

    private fun kotlinx.serialization.json.JsonObject.without(key: String) = with(key to null)

    @Test
    fun `an order with no filled quantity has none, and with no update time was updated when created`() {
        val bare = AlpacaMapping.order(limit.without("filled_qty").without("updated_at"))

        assertThat(bare.filledQuantity).isZero
        assertThat(bare.updatedAtMs).isEqualTo(bare.createdAtMs)
        assertThat(AlpacaMapping.order(limit.with("filled_qty" to "0.5")).filledQuantity).isEqualByComparingTo("0.5")
    }

    @Test
    fun `an order with no creation time is refused by name`() {
        assertThatThrownBy { AlpacaMapping.order(limit.without("created_at")) }.hasMessageContaining("created_at")
    }

    @Test
    fun `a rejected order says so, and only a rejected one does`() {
        val rejected = AlpacaMapping.order(limit.with("status" to "rejected"))
        val working = AlpacaMapping.order(limit)

        assertThat(rejected.status).isEqualTo(OrderStatus.REJECTED)
        assertThat(rejected.rejectReason).isEqualTo("rejected by alpaca")
        assertThat(working.rejectReason).isNull()
    }

    @Test
    fun `every status alpaca names maps to working, filled, cancelled or rejected`() {
        val working =
            listOf(
                "new",
                "partially_filled",
                "done_for_day",
                "accepted",
                "pending_new",
                "accepted_for_bidding",
                "pending_cancel",
                "pending_replace",
                "stopped",
                "suspended",
                "calculated",
                "held",
            )
        working.forEach {
            assertThat(
                AlpacaMapping.order(limit.with("status" to it)).status,
            ).describedAs(it).isEqualTo(OrderStatus.WORKING)
        }
        mapOf(
            "filled" to OrderStatus.FILLED,
            "canceled" to OrderStatus.CANCELLED,
            "expired" to OrderStatus.CANCELLED,
            "replaced" to OrderStatus.CANCELLED,
        ).forEach { (word, status) ->
            assertThat(AlpacaMapping.order(limit.with("status" to word)).status).describedAs(word).isEqualTo(status)
        }
    }

    @Test
    fun `an order or an update missing a required time or field is refused naming it`() {
        val update = Fixtures.updates("ws-trade-updates-fill.json").first { AlpacaMapping.fill(it) != null }
        val activity = Fixtures.array("activities-fill.json").first()

        assertThatThrownBy { AlpacaMapping.fill(update.without("timestamp")) }.hasMessageContaining("timestamp")
        assertThatThrownBy { AlpacaMapping.fill(update.without("execution_id")) }.hasMessageContaining("execution_id")
        assertThatThrownBy { AlpacaMapping.fill(activity.without("transaction_time"), "l") }
            .hasMessageContaining("transaction_time")
        assertThat(AlpacaMapping.fill(update.with("event" to "partial_fill"))).isNotNull
        assertThat(AlpacaMapping.fill(update.with("event" to "new"))).isNull()
        assertThat(AlpacaMapping.fill(update.without("event"))).isNull()
    }

    @Test
    fun `an account with no margin figures has none used, all its equity available, and no maintenance`() {
        val account =
            AlpacaMapping.account(
                Fixtures.obj("account.json").without("initial_margin").without("maintenance_margin"),
            )

        assertThat(account.marginUsed).isZero
        assertThat(account.marginAvailable).isEqualByComparingTo(account.equity)
        assertThat(account.maintenanceMargin).isNull()
    }

    @Test
    fun `a short position is held negative`() {
        val held = Fixtures.array("positions-crypto.json").single()

        assertThat(AlpacaMapping.position(held.with("side" to "short")).quantity.signum()).isEqualTo(-1)
        assertThat(AlpacaMapping.position(held.with("side" to "long", "qty" to "-2")).quantity.signum()).isEqualTo(1)
    }

    @Test
    fun `a quote side at zero or absent is no side, and a quote with no time is refused`() {
        val q = Fixtures.quotes("ws-data-crypto.json").first()

        val noBid = AlpacaMapping.quote(q.with("bp" to "0"))
        val noAsk = AlpacaMapping.quote(q.with("ap" to "0.0"))
        val absent = AlpacaMapping.quote(q.without("bp").without("ap"))

        assertThat(noBid.bid).isNull()
        assertThat(noBid.ask).isNotNull
        assertThat(noAsk.ask).isNull()
        assertThat(noAsk.bid).isNotNull
        assertThat(listOf(absent.bid, absent.ask)).containsOnlyNulls()
        assertThatThrownBy { AlpacaMapping.quote(q.without("t")) }.hasMessageContaining("without t")
    }

    @Test
    fun `an asset is long-only unless shortable, disabled unless tradable, and sized in fractions only when asked`() {
        val aapl = Fixtures.obj("asset-AAPL.json")

        val whole = AlpacaInstruments.instrument(aapl.with("fractionable" to "true"), fractional = false)
        val fraction = AlpacaInstruments.instrument(aapl.with("fractionable" to "true"), fractional = true)
        val notFractionable = AlpacaInstruments.instrument(aapl.with("fractionable" to "false"), fractional = true)
        val unknownFractionable = AlpacaInstruments.instrument(aapl.without("fractionable"), fractional = true)
        val untradable = AlpacaInstruments.instrument(aapl.with("tradable" to "false"))
        val noShort = AlpacaInstruments.instrument(aapl.with("tradable" to "true", "shortable" to "false"))
        val unknownTradable = AlpacaInstruments.instrument(aapl.without("tradable"))
        val coin = Fixtures.array("assets-crypto.json").first()

        assertThat(whole.volumeStep).isEqualByComparingTo("1")
        assertThat(fraction.volumeStep).isEqualByComparingTo("0.000000001")
        assertThat(fraction.volumeMin).isEqualByComparingTo("0.000000001")
        assertThat(notFractionable.volumeStep).isEqualByComparingTo("1")
        assertThat(unknownFractionable.volumeMin).isEqualByComparingTo("1")
        assertThat(untradable.trading).isEqualTo(Tradability.DISABLED)
        assertThat(noShort.trading).isEqualTo(Tradability.LONG_ONLY)
        assertThat(unknownTradable.trading).isEqualTo(Tradability.DISABLED)
        assertThat(
            AlpacaInstruments.instrument(coin.with("tradable" to "true")).trading,
        ).isEqualTo(Tradability.LONG_ONLY)
        assertThat(
            AlpacaInstruments.instrument(coin.with("tradable" to "false")).trading,
        ).isEqualTo(Tradability.DISABLED)
        assertThat(AlpacaInstruments.instrument(coin.without("tradable")).trading).isEqualTo(Tradability.DISABLED)
        assertThat(AlpacaInstruments.instrument(coin).kind).isEqualTo(InstrumentKind.SPOT)
        assertThatThrownBy { AlpacaInstruments.instrument(aapl.with("class" to "us_option")) }
            .hasMessageContaining("us_option")
        assertThat(Side.entries).contains(AlpacaMapping.side("sell_short"))
    }
}
