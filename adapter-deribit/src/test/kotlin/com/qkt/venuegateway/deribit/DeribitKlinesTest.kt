package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.market.DeribitMarketMapping
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeribitKlinesTest {
    private val minute = 60_000L
    private val asked = mutableListOf<Triple<Long, Long, Long>>()
    private val kline =
        DeribitKline(
            1_790_863_980_000L,
            BigDecimal("83805.7"),
            BigDecimal("83862.8"),
            BigDecimal("83758.1"),
            BigDecimal("83786.2"),
            BigDecimal("14.7756"),
        )

    private val market =
        object : DeribitMarketData {
            override fun klines(
                name: String,
                minutes: Long,
                fromMs: Long,
                toMs: Long,
            ): List<DeribitKline> {
                asked += Triple(minutes, fromMs, toMs)
                return listOf(kline)
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
    fun `deribit's kline lengths are served in milliseconds, from a minute to a day`() {
        assertThat(DeribitMarketMapping.klineResolutions).contains(minute, 120 * minute, 1_440 * minute).hasSize(12)
    }

    @Test
    fun `klines are asked in deribit's minutes up to the last millisecond of the range, and read as recorded`() {
        val from = kline.startMs
        val bars = DeribitMarketMapping.klines(market, "BTC_USDC-PERPETUAL", 120 * minute, from, from + 240 * minute)

        assertThat(asked).containsExactly(Triple(120L, from, from + 240 * minute - 1))
        val bar = bars.single()
        assertThat(bar.startMs).isEqualTo(kline.startMs)
        assertThat(listOf(bar.open, bar.high, bar.low, bar.close, bar.volume).map { it.toPlainString() })
            .containsExactly("83805.7", "83862.8", "83758.1", "83786.2", "14.7756")
    }

    @Test
    fun `an empty range still asks deribit, and what it answers outside the range is left out`() {
        val at = kline.startMs + 1
        val bars = DeribitMarketMapping.klines(market, "BTC_USDC-PERPETUAL", minute, at, at)

        assertThat(asked).containsExactly(Triple(1L, at, at))
        assertThat(bars).isEmpty()
    }
}
