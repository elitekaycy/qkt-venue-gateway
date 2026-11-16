package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.Tradability
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.kalshi.client.KalshiException
import com.qkt.venuegateway.kalshi.client.KalshiPortfolioJson
import com.qkt.venuegateway.kalshi.client.KalshiPriceRange
import java.math.BigDecimal
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KalshiMappingArmsTest {
    private val market = Fixtures.market("demo/events-KXNBA3D.json")
    private val order = KalshiPortfolioJson.order(Fixtures.json("private/order-resting.json")["order"]!!.jsonObject)
    private val one = BigDecimal.ONE
    private val half = BigDecimal("0.5")

    private fun new(
        type: OrderType,
        tif: TimeInForce,
    ) = NewOrder("x", market.ticker, Side.BUY, type, one, half, null, tif)

    @Test
    fun `a market or immediate-or-cancel order is immediate, a resting limit is not`() {
        assertThat(KalshiOrderMapping.immediate(new(OrderType.MARKET, TimeInForce.GTC))).isTrue
        assertThat(KalshiOrderMapping.immediate(new(OrderType.LIMIT, TimeInForce.IOC))).isTrue
        assertThat(KalshiOrderMapping.immediate(new(OrderType.LIMIT, TimeInForce.GTC))).isFalse
        assertThat(KalshiOrderMapping.immediate(new(OrderType.LIMIT, TimeInForce.FOK))).isFalse
    }

    @Test
    fun `an order read back keeps what the gateway sent it as, and one it never sent reads as a resting limit`() {
        val sent = KalshiOrderMapping.order(order, KalshiSent(OrderType.MARKET, TimeInForce.IOC, true))
        val foreign = KalshiOrderMapping.order(order, null)

        assertThat(sent.type).isEqualTo(OrderType.MARKET)
        assertThat(sent.limitPrice).isNull()
        assertThat(sent.timeInForce).isEqualTo(TimeInForce.IOC)
        assertThat(sent.reduceOnly).isTrue
        assertThat(foreign.type).isEqualTo(OrderType.LIMIT)
        assertThat(foreign.limitPrice).isEqualTo(order.yesPrice)
        assertThat(foreign.timeInForce).isEqualTo(TimeInForce.GTC)
        assertThat(foreign.reduceOnly).isFalse
    }

    @Test
    fun `a quote side with no price, a price at the edge, or an empty size is no side`() {
        val full = market.copy(yesBid = half, yesBidSize = one, yesAsk = BigDecimal("0.6"), yesAskSize = one)

        fun quote(m: com.qkt.venuegateway.kalshi.client.KalshiMarket) = KalshiMarketMapping.quote(m, 1L)

        assertThat(quote(full).bid).isEqualTo(half)
        assertThat(quote(full).ask).isEqualByComparingTo("0.6")
        assertThat(quote(full.copy(yesBidSize = null, yesAskSize = null)).let { it.bid to it.ask }).isEqualTo(
            half to BigDecimal("0.6"),
        )
        listOf(
            full.copy(yesBid = null),
            full.copy(yesBid = BigDecimal.ZERO),
            full.copy(yesBidSize = BigDecimal.ZERO),
        ).forEach { assertThat(quote(it).bid).isNull() }
        listOf(
            full.copy(yesAsk = null),
            full.copy(yesAsk = one),
            full.copy(yesAskSize = BigDecimal.ZERO),
        ).forEach { assertThat(quote(it).ask).isNull() }
    }

    @Test
    fun `a market on another shard is not traded here, one on this shard or naming none is`() {
        val active = market.copy(status = "active", mveCollection = null)

        assertThat(KalshiTerms.trading(active.copy(exchangeIndex = null), 0)).isEqualTo(Tradability.FULL)
        assertThat(KalshiTerms.trading(active.copy(exchangeIndex = 0), 0)).isEqualTo(Tradability.FULL)
        assertThat(KalshiTerms.trading(active.copy(exchangeIndex = 1), 0)).isEqualTo(Tradability.DISABLED)
    }

    @Test
    fun `the tick is the finest step of the price ranges, a cent when there are none`() {
        val ranges =
            listOf(
                KalshiPriceRange(BigDecimal("0"), BigDecimal("0.1"), BigDecimal("0.01")),
                KalshiPriceRange(BigDecimal("0.1"), BigDecimal("0.9"), BigDecimal("0.001")),
                KalshiPriceRange(BigDecimal("0.9"), BigDecimal("1"), BigDecimal("0.01")),
            )

        assertThat(KalshiMapping.tick(market.copy(priceRanges = emptyList()))).isEqualByComparingTo("0.01")
        assertThat(KalshiMapping.tick(market.copy(priceRanges = ranges))).isEqualByComparingTo("0.001")
    }

    @Test
    fun `kalshi's rate limit and server errors leave the outcome unknown, any other status is a refusal`() {
        fun failure(status: Int) = KalshiErrors.translate(KalshiException(status, "code", "words"))

        listOf(429, 500, 599).forEach {
            assertThat(failure(it)).describedAs("$it").isInstanceOf(VenueUnavailableException::class.java)
        }
        listOf(400, 404, 499, 600).forEach {
            assertThat(failure(it)).describedAs("$it").isInstanceOf(VenueRefusedException::class.java)
        }
    }
}
