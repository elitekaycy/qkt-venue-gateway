package com.qkt.venuegateway.deribit.client

import com.qkt.venuegateway.deribit.client.DeribitEdits.num
import com.qkt.venuegateway.deribit.client.DeribitEdits.text
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeribitJsonArmsTest {
    private val perpetual = recordedObject("instrument-SOL_USDC-PERPETUAL.json")
    private val dated = recordedObject("instrument-expired.json")
    private val ticker = recordedObject("ticker-perp.json")

    @Test
    fun `a decimal that is absent or null is none, and one present is read exact`() {
        assertThat(DeribitJson.decimal(null)).isNull()
        assertThat(DeribitJson.decimal(NULL)).isNull()
        assertThat(DeribitJson.decimal(num("0.10"))).isEqualTo(BigDecimal("0.10"))
    }

    @Test
    fun `an instrument missing a required field is refused naming the field`() {
        assertThatThrownBy { DeribitJson.instrument(perpetual.without("contract_size")) }
            .hasMessageContaining("contract_size")
        assertThatThrownBy { DeribitJson.instrument(perpetual.without("instrument_name")) }
            .hasMessageContaining("instrument_name")
        assertThatThrownBy { DeribitJson.instrument(perpetual.without("kind")) }.hasMessageContaining("kind")
        assertThatThrownBy { DeribitJson.instrument(perpetual.without("price_index")) }
            .hasMessageContaining("price_index")
        assertThatThrownBy { DeribitJson.instrument(perpetual.without("is_active")) }
            .hasMessageContaining("is_active")
        assertThatThrownBy { DeribitJson.instrument(dated.without("expiration_timestamp")) }
            .hasMessageContaining("expiration_timestamp")
    }

    @Test
    fun `an instrument settles in the counter currency when it names no settlement currency, else is refused`() {
        val counter =
            DeribitJson.instrument(
                perpetual.without("settlement_currency").set(
                    "counter_currency" to text("USDC"),
                ),
            )

        assertThat(counter.settlementCurrency).isEqualTo("USDC")
        assertThatThrownBy {
            DeribitJson.instrument(perpetual.without("settlement_currency", "counter_currency"))
        }.hasMessageContaining("currency missing")
    }

    @Test
    fun `only a perpetual has no expiry, and a dated one carries its expiration`() {
        assertThat(DeribitJson.instrument(perpetual).expiryMs).isNull()
        assertThat(DeribitJson.instrument(dated).expiryMs).isEqualTo(dated["expiration_timestamp"].toString().toLong())
    }

    @Test
    fun `a ticker side with no price or no amount, or either zero, is no side`() {
        val noBidPrice = DeribitJson.ticker(ticker.without("best_bid_price"))
        val noBidAmount = DeribitJson.ticker(ticker.without("best_bid_amount"))
        val zeroPrice = DeribitJson.ticker(ticker.set("best_ask_price" to num("0")))
        val zeroAmount = DeribitJson.ticker(ticker.set("best_ask_amount" to num("0")))
        val whole = DeribitJson.ticker(ticker)

        assertThat(listOf(noBidPrice.bid, noBidPrice.bidAmount)).containsOnlyNulls()
        assertThat(listOf(noBidAmount.bid, noBidAmount.bidAmount)).containsOnlyNulls()
        assertThat(listOf(zeroPrice.ask, zeroPrice.askAmount)).containsOnlyNulls()
        assertThat(listOf(zeroAmount.ask, zeroAmount.askAmount)).containsOnlyNulls()
        assertThat(whole.bid).isNotNull
        assertThat(whole.ask).isNotNull
    }

    @Test
    fun `a ticker or order book naming no instrument, or a record missing its time, is refused`() {
        assertThatThrownBy { DeribitJson.ticker(ticker.without("instrument_name")) }
            .hasMessageContaining("instrument_name")
        assertThatThrownBy { DeribitJson.ticker(ticker.without("timestamp")) }.hasMessageContaining("timestamp")
        assertThatThrownBy { DeribitJson.orderBook(recordedObject("order-book-perp.json").without("instrument_name")) }
            .hasMessageContaining("instrument_name")
    }

    @Test
    fun `an order book level holding fewer than a price and an amount is refused`() {
        val book = recordedObject("order-book-perp.json")
        val short = JsonArray(listOf(JsonArray(listOf(num("1")))))

        assertThatThrownBy {
            DeribitJson.orderBook(
                book.set("bids" to short),
            )
        }.isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `a public trade missing its id or direction is refused naming it`() {
        val trades = recordedObject("trades-from-time.json")
        val first = (trades["trades"] as JsonArray).first() as JsonObject

        fun with(trade: JsonObject) = JsonObject(trades + ("trades" to JsonArray(listOf(trade))))

        assertThatThrownBy { DeribitJson.publicTrades(with(first.without("trade_id"))) }
            .hasMessageContaining("trade_id")
        assertThatThrownBy { DeribitJson.publicTrades(with(first.without("direction"))) }
            .hasMessageContaining("direction")
        assertThat(
            DeribitJson.publicTrades(with(first.set("liquidation" to text("T")))).single().liquidation,
        ).isEqualTo("T")
        assertThat(DeribitJson.publicTrades(with(first)).single().liquidation).isNull()
    }
}
