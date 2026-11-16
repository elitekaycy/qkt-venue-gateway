package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.kalshi.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The fields Kalshi leaves out on some answers, read from recorded answers with those fields taken away. */
class KalshiJsonTest {
    private fun msg(name: String) = Fixtures.json("socket/$name.json")["msg"]!!.jsonObject

    private fun first(
        name: String,
        key: String,
    ) = Fixtures.json(name)[key]!!.jsonArray[0].jsonObject

    private fun JsonObject.without(vararg keys: String) = JsonObject(this - keys.toSet())

    @Test
    fun `a ticker or a fill without milliseconds is stamped from its seconds`() {
        assertThat(KalshiMarketJson.ticker(msg("ticker").without("ts_ms")).timeMs).isEqualTo(1_793_927_034_000L)
        val fill = KalshiPortfolioJson.fill(msg("fill").without("ts_ms", "fee_cost", "client_order_id"))
        assertThat(fill.timeMs).isEqualTo(1_793_927_017_000L)
        assertThat(fill.fee).isZero
        assertThat(fill.clientOrderId).isNull()
    }

    @Test
    fun `an order without times is stamped 0, and one never updated at its creation`() {
        val o = first("private/orders-resting.json", "orders")

        assertThat(KalshiPortfolioJson.order(o.without("created_time", "last_update_time")).createdMs).isZero
        val created = KalshiPortfolioJson.order(o.without("last_update_time"))
        assertThat(created.updatedMs).isEqualTo(created.createdMs)
    }

    @Test
    fun `a market without an expected expiry expires at its latest, and absent fields read absent`() {
        val m = first("demo/events-KXNBA3D.json", "events")["markets"]!!.jsonArray[0].jsonObject
        val bare = KalshiMarketJson.market(m.without("expected_expiration_time", "exchange_index", "can_close_early"))
        val none = KalshiMarketJson.market(m.without("expected_expiration_time", "latest_expiration_time"))

        assertThat(bare.expectedExpirationMs).describedAs("latest_expiration_time").isNotNull
        assertThat(bare.exchangeIndex).isNull()
        assertThat(bare.canCloseEarly).isFalse
        assertThat(none.expectedExpirationMs).isNull()
        val event = first("demo/events-KXNBA3D.json", "events").without("mutually_exclusive")
        assertThat(KalshiMarketJson.event(event).mutuallyExclusive).isFalse
    }

    @Test
    fun `a candle without a side has no prices there, and a book without a side or its body`() {
        val candle = first("public/candles-1m-KXHIGHNY-26NOV06-B67.5.json", "candlesticks").without("yes_bid")
        val book = Fixtures.json("public/orderbook-KXHIGHNY-26NOV06-B67.5.json")
        val noYes = JsonObject(mapOf("orderbook_fp" to book["orderbook_fp"]!!.jsonObject.without("yes_dollars")))

        assertThat(KalshiMarketJson.candle(candle).yesBid.close).isNull()
        assertThat(KalshiMarketJson.book(noYes).yes).isEmpty()
        assertThatThrownBy { KalshiMarketJson.book(book.without("orderbook_fp")) }.hasMessageContaining("orderbook_fp")
    }

    @Test
    fun `a settlement reads its result and value, and fails without its time`() {
        val s = first("private/settlements.json", "settlements")

        assertThat(KalshiPortfolioJson.settlement(s).valueCents).isZero
        assertThat(KalshiPortfolioJson.settlement(s.without("value", "market_result")).marketResult).isEmpty()
        assertThatThrownBy { KalshiPortfolioJson.settlement(s.without("settled_time")) }
            .hasMessageContaining("without settled_time")
    }
}
