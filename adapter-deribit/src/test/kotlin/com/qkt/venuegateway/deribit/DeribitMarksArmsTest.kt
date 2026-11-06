package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarkTrade
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPage
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.market.DeribitMarks
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeribitMarksArmsTest {
    private fun trade(
        seq: Long,
        time: Long,
        mark: String,
    ) = DeribitMarkTrade(seq, time, BigDecimal(mark), BigDecimal("1"))

    private class Scripted(
        private val first: DeribitMarkTrade?,
        private val last: DeribitMarkTrade?,
        private val page: List<DeribitMarkTrade> = emptyList(),
    ) : DeribitMarketData {
        override fun instruments(
            currency: String,
            kind: String,
        ): List<DeribitInstrument> = error("unused")

        override fun instrument(name: String): DeribitInstrument = error("unused")

        override fun ticker(name: String): DeribitTicker = error("unused")

        override fun klines(
            name: String,
            minutes: Long,
            fromMs: Long,
            toMs: Long,
        ): List<DeribitKline> = error("unused")

        override fun deliveryPrices(
            index: String,
            count: Int,
        ): List<Pair<LocalDate, BigDecimal>> = error("unused")

        override fun edgeTrade(
            name: String,
            fromMs: Long,
            toMs: Long,
            newest: Boolean,
        ) = if (newest) last else first

        override fun tradesFrom(
            name: String,
            fromMs: Long,
            toMs: Long,
            count: Int,
        ) = DeribitPage(page, false)
    }

    @Test
    fun `a range with no trade at its start or at its end has no marks`() {
        val some = trade(10, 1_000, "5")

        assertThat(DeribitMarks.sampled(Scripted(null, null), "X", 1_000, 0, 5_000)).isEmpty()
        assertThat(DeribitMarks.sampled(Scripted(some, null), "X", 1_000, 0, 5_000)).isEmpty()
    }

    @Test
    fun `a page that strays outside the first and last trades contributes only the trades between them`() {
        val first = trade(10, 1_000, "5")
        val last = trade(12, 2_000, "7")
        val page = listOf(trade(9, 1_000, "99"), first, trade(11, 2_000, "6"), last, trade(13, 2_000, "98"))

        val marks = DeribitMarks.sampled(Scripted(first, last, page), "X", 1_000, 0, 5_000)

        assertThat(marks.map { it.timeMs }).containsExactly(1_000L, 2_000L)
        assertThat(marks.map { it.mark!!.toPlainString() }).containsExactly("5", "7")
    }
}
