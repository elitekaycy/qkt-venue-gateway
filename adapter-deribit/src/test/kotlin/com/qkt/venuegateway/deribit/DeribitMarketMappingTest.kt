package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeribitMarketMappingTest {
    private fun kline(startMs: Long) =
        DeribitKline(startMs, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE)

    @Test
    fun `only closed bars starting inside the window are served, never the forming one or the one before it`() {
        val minute = 60_000L
        val klines = listOf(kline(0), kline(minute), kline(2 * minute), kline(3 * minute))

        val forming = DeribitMarketMapping.closedBars(klines, minute, 30_000, 10 * minute, nowMs = 3 * minute + 30_000)
        val windowed = DeribitMarketMapping.closedBars(klines, minute, minute, 3 * minute, nowMs = 10 * minute)

        assertThat(forming.map { it.startMs }).containsExactly(minute, 2 * minute)
        assertThat(windowed.map { it.startMs }).containsExactly(minute, 2 * minute)
    }

    @Test
    fun `a ticker becomes a quote with every value Deribit reported and none it did not`() {
        val ticker =
            DeribitTicker(
                "BTC_USDC-2OCT26-82000-P",
                7,
                BigDecimal("100"),
                BigDecimal("2"),
                null,
                null,
                BigDecimal("101"),
                BigDecimal("48.5"),
                BigDecimal("83000"),
                null,
            )

        val quote = DeribitMarketMapping.quote(ticker)

        assertThat(quote.symbol).isEqualTo("BTC_USDC-2OCT26-82000-P")
        assertThat(quote.timeMs).isEqualTo(7)
        assertThat(quote.bid).isEqualByComparingTo("100")
        assertThat(quote.bidSize).isEqualByComparingTo("2")
        assertThat(quote.ask).isNull()
        assertThat(quote.askSize).isNull()
        assertThat(quote.mark).isEqualByComparingTo("101")
        assertThat(quote.markIv).isEqualByComparingTo("48.5")
        assertThat(quote.underlying).isEqualByComparingTo("83000")
    }
}
