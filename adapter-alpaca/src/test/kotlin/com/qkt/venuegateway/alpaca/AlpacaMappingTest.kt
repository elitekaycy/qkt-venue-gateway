package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.Tradability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AlpacaMappingTest {
    @Test
    fun `a share is a whole-share equity on its exchange, long-only unlent, disabled when untradable`() {
        val byCode = Fixtures.array("assets-us-equity.json").map(AlpacaInstruments::instrument).associateBy { it.code }
        val aapl = byCode.getValue("AAPL")
        assertThat(aapl.kind).isEqualTo(InstrumentKind.EQUITY)
        assertThat(aapl.category).isEqualTo("NASDAQ")
        assertThat(aapl.trading).isEqualTo(Tradability.FULL)
        assertThat(listOf(aapl.contractSize, aapl.volumeStep, aapl.volumeMin)).allSatisfy {
            assertThat(it).isEqualByComparingTo("1")
        }
        assertThat(aapl.tickSize).isEqualByComparingTo("0.01")
        assertThat(byCode.getValue("VFS").trading).isEqualTo(Tradability.LONG_ONLY)
        assertThat(byCode.getValue("HYB").trading).isEqualTo(Tradability.DISABLED)
        assertThat(byCode.getValue("SPY").category).isEqualTo("ARCA")
    }

    @Test
    fun `a coin pair is long-only spot under its symbol without the slash, sized by Alpaca's increments`() {
        val btc =
            AlpacaInstruments.instrument(
                Fixtures.array("assets-crypto.json").single {
                    it.text("symbol") ==
                        "BTC/USD"
                },
            )
        assertThat(btc.code).isEqualTo("BTCUSD")
        assertThat(btc.kind).isEqualTo(InstrumentKind.SPOT)
        assertThat(btc.currency).isEqualTo("USD")
        assertThat(btc.trading).isEqualTo(Tradability.LONG_ONLY)
        assertThat(btc.volumeMin.toPlainString()).isEqualTo("0.000012504")
        assertThat(btc.volumeStep.toPlainString()).isEqualTo("0.000000001")
    }

    @Test
    fun `a resting limit maps every field exactly, and its label is the id it was placed with`() {
        val order = AlpacaMapping.order(Fixtures.obj("order-limit-accepted.json"))
        assertThat(order.clientOrderId).startsWith("qkt-rest-")
        assertThat(order.symbol).isEqualTo("AAPL")
        assertThat(order.side).isEqualTo(Side.BUY)
        assertThat(order.type).isEqualTo(OrderType.LIMIT)
        assertThat(order.timeInForce).isEqualTo(TimeInForce.DAY)
        assertThat(order.status).isEqualTo(OrderStatus.WORKING)
        assertThat(order.limitPrice).isEqualByComparingTo("1")
        assertThat(order.filledQuantity).isZero
        assertThat(order.reduceOnly).isFalse
    }

    @Test
    fun `a filled coin order carries its exact average, a cancelled one is final, a replacement keeps the label`() {
        val filled = AlpacaMapping.order(Fixtures.obj("order-crypto-filled.json"))
        assertThat(filled.symbol).isEqualTo("BTCUSD")
        assertThat(filled.status).isEqualTo(OrderStatus.FILLED)
        assertThat(filled.avgFillPrice!!.toPlainString()).isEqualTo("80816.35322095")
        assertThat(
            AlpacaMapping.order(Fixtures.obj("order-by-label-cancelled.json")).status,
        ).isEqualTo(OrderStatus.CANCELLED)
        val original = Fixtures.obj("order-by-label-after-replace.json")
        val replacement = AlpacaMapping.order(Fixtures.obj("order-crypto-replaced.json"))
        assertThat(replacement.clientOrderId).isEqualTo(original.text("client_order_id"))
    }

    @Test
    fun `stream fills and FILL activities name one execution by the same id, so the host counts it once`() {
        val streamed = Fixtures.updates("ws-trade-updates-fill.json").mapNotNull(AlpacaMapping::fill)
        val bought = streamed.filter { it.side == Side.BUY }
        assertThat(bought).hasSize(2)
        assertThat(bought.fold(java.math.BigDecimal.ZERO) { a, f -> a + f.quantity }).isEqualByComparingTo("0.0002")
        val activities = Fixtures.array("activities-fill.json").map { AlpacaMapping.fill(it, "label") }
        assertThat(activities.map { it.fillId }).containsAll(bought.map { it.fillId })
        assertThat(activities.first().price).isEqualByComparingTo(bought.first().price)
        assertThat(
            Fixtures
                .updates("ws-trade-updates-fill.json")
                .filter {
                    AlpacaMapping.fill(it) == null
                }.map { it.text("event") },
        ).contains("pending_new", "new", "replaced", "canceled")
    }

    @Test
    fun `the account, a position, a quote and a bar read Alpaca's decimals exactly`() {
        val account = AlpacaMapping.account(Fixtures.obj("account.json"))
        assertThat(account.currency).isEqualTo("USD")
        assertThat(account.equity).isEqualByComparingTo(account.marginAvailable + account.marginUsed)
        val position = AlpacaMapping.position(Fixtures.array("positions-crypto.json").single())
        assertThat(position.symbol).isEqualTo("BTCUSD")
        assertThat(position.quantity.toPlainString()).isEqualTo("0.000199499")
        val quote = AlpacaMapping.quote(Fixtures.quotes("ws-data-crypto.json").first())
        assertThat(quote.symbol).isEqualTo("BTCUSD")
        assertThat(quote.bid!!).isLessThanOrEqualTo(quote.ask)
        val bars = (Fixtures.obj("bars-stocks-1min-page.json")["bars"] as JsonObject)["AAPL"] as JsonArray
        val bar = AlpacaMapping.bar(bars[1] as JsonObject)
        assertThat(bar.open).isEqualByComparingTo("343.11")
        assertThat(bar.volume).isEqualByComparingTo("310")
    }

    @Test
    fun `an order status, type or time in force qkt cannot hold fails naming it`() {
        val order = Fixtures.obj("order-limit-accepted.json")

        fun with(
            key: String,
            value: String,
        ) = JsonObject(order + (key to JsonPrimitive(value)))
        assertThatThrownBy { AlpacaMapping.order(with("status", "frozen")) }.hasMessageContaining("status frozen")
        assertThatThrownBy { AlpacaMapping.order(with("type", "trailing_stop")) }.hasMessageContaining("trailing_stop")
        assertThatThrownBy { AlpacaMapping.order(with("time_in_force", "opg")) }.hasMessageContaining("opg")
    }

    @Test
    fun `a short's fill activity reads as a sell, and its cover as a buy`() {
        val activities = Fixtures.array("activities-fill-short.json")
        // A long round trip, a coin round trip, then a short opened and covered: the venue's own sides, in order.
        assertThat(
            activities.map { it.text("side") },
        ).containsExactly("buy", "sell", "buy", "sell", "sell_short", "buy")
        val sides = activities.map { AlpacaMapping.fill(it, "label").side }
        assertThat(sides).containsExactly(Side.BUY, Side.SELL, Side.BUY, Side.SELL, Side.SELL, Side.BUY)
        assertThatThrownBy { AlpacaMapping.side("short_exempt") }.hasMessageContaining("short_exempt")
    }

    @Test
    fun `a fractionable share is sized in billionths only when the account trades fractions`() {
        val aapl = Fixtures.obj("asset-AAPL.json")
        assertThat(AlpacaInstruments.instrument(aapl).volumeStep).isEqualByComparingTo("1")
        val fractional = AlpacaInstruments.instrument(aapl, fractional = true)
        assertThat(fractional.volumeStep.toPlainString()).isEqualTo("0.000000001")
        assertThat(fractional.volumeMin.toPlainString()).isEqualTo("0.000000001")
        val whole = Fixtures.array("assets-us-equity.json").single { it.text("symbol") == "AV" }
        assertThat(AlpacaInstruments.instrument(whole, fractional = true).volumeStep).isEqualByComparingTo("1")
    }

    private fun JsonObject.text(key: String) = (this[key] as JsonPrimitive).content
}
