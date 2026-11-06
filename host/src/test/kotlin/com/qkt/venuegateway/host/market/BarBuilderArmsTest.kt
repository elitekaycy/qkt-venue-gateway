package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.host.FakeAdapter
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BarBuilderArmsTest {
    private val minute = 60_000L
    private val asked = mutableListOf<Pair<Long, Long>>()

    /** A venue of one-minute klines that answers [served] whatever range it is asked, as a sloppy venue may. */
    private fun venue(served: List<VenueBar>): VenueAdapter =
        object : VenueAdapter by FakeAdapter() {
            override val klineResolutions = setOf(minute)

            override fun klines(
                code: String,
                resolutionMs: Long,
                fromMs: Long,
                toMs: Long,
            ): List<VenueBar> {
                asked += fromMs to toMs
                return served
            }
        }

    private fun kline(
        minuteIndex: Long,
        high: String,
        low: String,
    ) = VenueBar(
        minuteIndex * minute,
        BigDecimal("10"),
        BigDecimal(high),
        BigDecimal(low),
        BigDecimal("10"),
        BigDecimal.ONE,
    )

    @Test
    fun `klines a venue sends before or after the range asked are dropped`() {
        val served = (0L..9L).map { kline(it, "11", "9") }

        val bars = BarBuilder.closed(venue(served), "X", minute, 3 * minute, 6 * minute, nowMs = 100 * minute)

        assertThat(bars.map { it.startMs / minute }).containsExactly(3L, 4L, 5L)
    }

    @Test
    fun `a window built of several klines takes the highest high, the lowest low and the sum of their volume`() {
        val served = listOf(kline(0, "12", "9"), kline(1, "15", "8"), kline(2, "11", "9.5"))

        val bars = BarBuilder.closed(venue(served), "X", 3 * minute, 0, 3 * minute, nowMs = 100 * minute)

        assertThat(bars).hasSize(1)
        assertThat(bars.single().high).isEqualByComparingTo("15")
        assertThat(bars.single().low).isEqualByComparingTo("8")
        assertThat(bars.single().volume).isEqualByComparingTo("3")
    }

    @Test
    fun `a range with no closed window yet has no bars and asks the venue for an empty range`() {
        val bars =
            BarBuilder.closed(
                venue(listOf(kline(0, "11", "9"))),
                "X",
                5 * minute,
                0,
                5 * minute,
                nowMs =
                    2 * minute,
            )

        assertThat(bars).isEmpty()
        assertThat(asked.single().first).isEqualTo(asked.single().second)
    }
}
