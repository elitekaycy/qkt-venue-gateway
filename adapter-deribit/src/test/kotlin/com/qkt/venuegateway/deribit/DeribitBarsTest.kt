package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeribitBarsTest {
    private val minute = 60_000L
    private val asked = mutableListOf<Long>()

    /** Deribit's klines at each resolution: price = the kline's minute index, one per base step. */
    private val market =
        object : DeribitMarketData {
            override fun klines(
                name: String,
                minutes: Long,
                fromMs: Long,
                toMs: Long,
            ): List<DeribitKline> {
                asked += minutes
                val step = minutes * minute
                return (fromMs / step * step..toMs step step).map {
                    val p = BigDecimal(it / minute)
                    DeribitKline(it, p, p + BigDecimal.ONE, p - BigDecimal.ONE, p, BigDecimal("2"))
                }
            }

            override fun instruments(
                currency: String,
                kind: String,
            ) = emptyList<DeribitInstrument>()

            override fun instrument(name: String) = error("unused")

            override fun ticker(name: String): DeribitTicker = error("unused")

            override fun deliveryPrices(
                index: String,
                count: Int,
            ) = emptyList<Pair<LocalDate, BigDecimal>>()
        }

    @Test
    fun `a window deribit serves is its klines, closed and inside the range, never the forming one`() {
        val bars = DeribitBars.closed(market, "X", minute, minute / 2, 10 * minute, nowMs = 3 * minute + 30_000)

        assertThat(asked).containsExactly(1L)
        assertThat(bars.map { it.startMs }).containsExactly(minute, 2 * minute)
    }

    @Test
    fun `a window deribit lacks is built from the largest resolution dividing it, first open to last close`() {
        val fourHours = 240 * minute
        val bars = DeribitBars.closed(market, "X", fourHours, 0, 3 * fourHours, nowMs = 3 * fourHours + 1)

        assertThat(asked).containsExactly(120L)
        assertThat(bars.map { it.startMs }).containsExactly(0L, fourHours, 2 * fourHours)
        val second = bars[1]
        assertThat(second.open).isEqualByComparingTo("240")
        assertThat(second.close).isEqualByComparingTo("360")
        assertThat(second.high).isEqualByComparingTo("361")
        assertThat(second.low).isEqualByComparingTo("239")
        assertThat(second.volume).isEqualByComparingTo("4")
    }

    @Test
    fun `an aggregated window still open is not served, and an eight-minute window is built from minutes`() {
        val eight = 8 * minute
        val bars = DeribitBars.closed(market, "X", eight, 0, 3 * eight, nowMs = 2 * eight + minute)

        assertThat(asked).containsExactly(1L)
        assertThat(bars.map { it.startMs }).containsExactly(0L, eight)
        assertThat(bars.first().volume).isEqualByComparingTo("16")
    }
}
