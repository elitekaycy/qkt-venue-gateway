package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.CURRY
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.RESTING
import com.qkt.venuegateway.kalshi.client.KalshiMarketJson
import com.qkt.venuegateway.kalshi.client.KalshiOhlc
import java.math.BigDecimal
import java.nio.file.Files
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The less travelled paths, each from a recorded answer: one-sided candles and quotes, overrides, a restart. */
class KalshiEdgesTest {
    private val venue = KalshiVenue()

    @AfterEach
    fun stop() = venue.close()

    private val candle =
        KalshiMarketJson.candle(
            Fixtures.json("public/candles-1m-KXHIGHNY-26NOV06-B67.5.json")["candlesticks"]!!.jsonArray[0].jsonObject,
        )

    @Test
    fun `a candle missing a bid price, or whose ask reached 1, has no bar`() {
        val noLow = candle.copy(yesBid = candle.yesBid.copy(low = null))
        val noAsk = candle.copy(yesAsk = KalshiOhlc(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))
        val askGone = candle.copy(yesAsk = candle.yesAsk.copy(close = null))

        assertThat(KalshiMarketMapping.bar(noLow, 60_000L)).isNull()
        assertThat(KalshiMarketMapping.bar(noAsk, 60_000L)).isNull()
        assertThat(KalshiMarketMapping.bar(askGone, 60_000L)).isNull()
        assertThat(KalshiMarketMapping.bar(candle, 60_000L)).isNotNull
    }

    @Test
    fun `a quote side without a size is kept, and an ask under 1 with nothing resting is absent`() {
        val t = KalshiMarketJson.ticker(Fixtures.json("socket/ticker.json")["msg"]!!.jsonObject)

        val unsized = KalshiMarketMapping.quote(t.copy(yesBidSize = null, yesAskSize = null))
        val empty = KalshiMarketMapping.quote(t.copy(yesAskSize = BigDecimal.ZERO, yesBid = null))

        assertThat(unsized.bid).isEqualByComparingTo("0.02")
        assertThat(unsized.ask).isEqualByComparingTo("0.04")
        assertThat(empty.ask).isNull()
        assertThat(empty.bid).isNull()
    }

    @Test
    fun `an event's fee override wins, and a market without an expected result expires at its close`() {
        val e = Fixtures.json("public/events-page-2.json")["events"]!!.jsonArray[0].jsonObject
        val event = KalshiMarketJson.event(e)
        val m = event.markets[0].copy(expectedExpirationMs = null)
        val overridden =
            event.copy(feeTypeOverride = "quadratic_with_maker_fees", feeMultiplierOverride = BigDecimal("0.5"))

        val i = KalshiMapping.instrument(m, overridden, null, 0)

        assertThat(i.expiryMs).isEqualTo(m.closeTimeMs)
        assertThat(i.event!!.fee!!.makerRate).isEqualByComparingTo("0.00875")
        assertThat(KalshiMapping.terms(m, event.copy(eventTicker = "KXHIGHNY-"), null).occasion).isNull()
    }

    @Test
    fun `a shard below 0 is refused by its key`() {
        val settings = mapOf("environment" to "demo", "shard" to "-1")
        val context = AdapterContext(settings, { 0L }, Files.createTempDirectory("k"))
        assertThatThrownBy { KalshiSettings.of(context) }.hasMessageContaining("setting shard must be")
    }

    @Test
    fun `after a restart a label is found by the id kept for it, and a price alone can change`() {
        val dir = Files.createTempDirectory("kalshi-restart")
        val key = Credentials("key-id", Fixtures.pem)
        val context = { AdapterContext(mapOf("environment" to "demo"), { 0L }, dir, key) }
        val factory = KalshiAdapterFactory { venue.server.restUrl to venue.server.socketUrl }
        factory.create(context()).use { first ->
            val price = BigDecimal("0.01")
            val one = BigDecimal.ONE
            first.place(NewOrder("qkt-probe-rest", CURRY, Side.BUY, OrderType.LIMIT, one, price, null, TimeInForce.GTC))
        }

        factory.create(context()).use { again ->
            val before = venue.server.requests.size
            assertThat(again.orderByLabel("qkt-probe-rest")!!.venueOrderId).isEqualTo(RESTING)
            val after = venue.server.requests.drop(before)
            assertThat(after.map { it.url.encodedPath })
                .containsExactly("/trade-api/v2/portfolio/orders/$RESTING")
            assertThat(again.modify("qkt-probe-rest", OrderChange(limitPrice = BigDecimal("0.03")))).isTrue
        }
    }

    @Test
    fun `a fill of an order learnt from the account's list takes its label, and out-of-range prints are left out`() {
        venue.adapter().use { adapter ->
            adapter.orderByLabel("nobody")
            val fills = adapter.fills(0L, Long.MAX_VALUE / 2)
            assertThat(fills.first().clientOrderId).isEqualTo("qkt-probe-fill")
            assertThat(adapter.trades(CURRY, 1_793_927_018_000L, 1_793_930_400_000L, 10)).isEmpty()
            assertThat(adapter.settlements(1_793_950_000_000L, 1_793_960_000_000L).map { it.symbol })
                .containsExactly("KXWTIH-26NOV0601-T91.49")
        }
    }
}
