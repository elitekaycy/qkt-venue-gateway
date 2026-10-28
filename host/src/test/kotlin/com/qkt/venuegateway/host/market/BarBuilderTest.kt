package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.host.FakeAdapter
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BarBuilderTest {
    private val minute = 60_000L
    private val asked = mutableListOf<Triple<Long, Long, Long>>()

    /** A venue serving [resolutions] (in minutes): price = the kline's minute index, volume 2, one per step. */
    private fun venue(vararg resolutions: Long): VenueAdapter =
        object : VenueAdapter by FakeAdapter() {
            override val klineResolutions = resolutions.map { it * minute }.toSet()

            override fun klines(
                code: String,
                resolutionMs: Long,
                fromMs: Long,
                toMs: Long,
            ): List<VenueBar> {
                asked += Triple(resolutionMs / minute, fromMs, toMs)
                return (fromMs until toMs step resolutionMs).map {
                    val p = BigDecimal(it / minute)
                    VenueBar(it, p, p + BigDecimal.ONE, p - BigDecimal.ONE, p, BigDecimal("2"))
                }
            }
        }

    private val deribitLike = venue(1, 3, 5, 10, 15, 30, 60, 120, 180, 360, 720, 1_440)

    @Test
    fun `a window the venue serves is its klines, closed and inside the range, never the forming one`() {
        val bars = BarBuilder.closed(deribitLike, "X", minute, minute / 2, 10 * minute, nowMs = 3 * minute + 30_000)

        assertThat(asked.map { it.first }).containsExactly(1L)
        assertThat(bars.map { it.startMs }).containsExactly(minute, 2 * minute)
    }

    @Test
    fun `a window the venue lacks is built from the largest resolution dividing it, first open to last close`() {
        val fourHours = 240 * minute
        val bars = BarBuilder.closed(deribitLike, "X", fourHours, 0, 3 * fourHours, nowMs = 3 * fourHours + 1)

        assertThat(asked.map { it.first }).containsExactly(120L)
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
        val bars = BarBuilder.closed(deribitLike, "X", eight, 0, 3 * eight, nowMs = 2 * eight + minute)

        assertThat(asked.map { it.first }).containsExactly(1L)
        assertThat(bars.map { it.startMs }).containsExactly(0L, eight)
        assertThat(bars.first().volume).isEqualByComparingTo("16")
    }

    @Test
    fun `a window straddling the end of the range is built from all its klines, not cut where the range ends`() {
        val four = 4 * minute
        val bars = BarBuilder.closed(deribitLike, "X", four, 30_000, 2 * four + 30_000, nowMs = 10 * four)

        assertThat(asked).containsExactly(Triple(1L, four, 3 * four))
        val straddling = bars.single { it.startMs == 2 * four }
        assertThat(straddling.close).isEqualByComparingTo("11")
        assertThat(straddling.volume).isEqualByComparingTo("8")
    }

    @Test
    fun `the venue is asked for the klines of the closed windows in range alone`() {
        BarBuilder.closed(venue(1, 5), "X", 10 * minute, 7 * minute, 60 * minute, nowMs = 41 * minute)

        assertThat(asked).containsExactly(Triple(5L, 10 * minute, 40 * minute))
    }

    @Test
    fun `a range holding no closed window still asks the venue, so it can refuse a code it does not serve`() {
        val refusing =
            object : VenueAdapter by deribitLike {
                override fun klines(
                    code: String,
                    resolutionMs: Long,
                    fromMs: Long,
                    toMs: Long,
                ): List<VenueBar> {
                    asked += Triple(resolutionMs / minute, fromMs, toMs)
                    throw VenueRefusedException("$code is not listed")
                }
            }

        assertThatThrownBy {
            BarBuilder.closed(refusing, "NOPE", minute, 0, 10 * minute, nowMs = 30_000)
        }.isInstanceOf(VenueRefusedException::class.java)
        assertThat(asked).containsExactly(Triple(1L, 0L, 0L))
    }

    @Test
    fun `a window no kline of the venue divides is refused, naming the window`() {
        assertThatThrownBy { BarBuilder.closed(venue(5), "X", 3 * minute, 0, 9 * minute, nowMs = 9 * minute) }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("180000")
    }
}
