package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarkTrade
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPage
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Marks sampled from the recorded testnet tape of BTC_USDC-PERPETUAL (`trades-from-time.json`: 181 trades over
 * three minutes from 2026-10-04 23:00:00Z); the last trade of each minute is the one Deribit answered for it
 * (`trades-last-in-minute-*.json`). `trades-history-mainnet-slice.json` is mainnet history-host trades sharing
 * milliseconds out of sequence order, as recorded.
 */
class DeribitMarksTest {
    private val minute = 60_000L
    private val from = 1_791_154_800_000L
    private val tape = Tape(trades("trades-from-time.json"))

    private fun trades(name: String): List<DeribitMarkTrade> =
        DeribitJson.markTrades(
            Json
                .parseToJsonElement(
                    javaClass.getResource("/fixtures/$name")!!.readText(),
                ).jsonObject["result"]!!
                .jsonObject,
        )

    @Test
    fun `each window's sample is its last trade's mark and index, at that trade's time`() {
        val marks = DeribitMarks.sampled(tape, "BTC_USDC-PERPETUAL", minute, from, from + 3 * minute)

        val expected = (0..2).map { trades("trades-last-in-minute-$it.json").single() }
        assertThat(marks.map { it.timeMs }).containsExactlyElementsOf(expected.map { it.timestampMs })
        assertThat(marks.map { it.mark!!.toPlainString() }).containsExactly("86482.3", "86440.68", "86412.98")
        assertThat(marks.map { it.index!!.toPlainString() }).containsExactly("86451.59", "86415.59", "86386.86")
    }

    @Test
    fun `a range of fewer trades than windows times a thousand is read in time order in one call`() {
        DeribitMarks.sampled(tape, "BTC_USDC-PERPETUAL", minute, from, from + 3 * minute)

        assertThat(tape.pageCalls).containsExactly(1_791_154_801_271L to 1_791_154_972_540L)
        assertThat(tape.edgeCalls).hasSize(2)
    }

    @Test
    fun `reading window by window, when it costs fewer calls, gives the same samples`() {
        val inPages = DeribitMarks.sampled(tape, "BTC_USDC-PERPETUAL", minute, from, from + 3 * minute)
        val byWindow = Tape(tape.trades)

        val marks = DeribitMarks.sampled(byWindow, "BTC_USDC-PERPETUAL", minute, from, from + 3 * minute, perCall = 50)

        assertThat(marks).isEqualTo(inPages)
        assertThat(byWindow.pageCalls).isEmpty()
        assertThat(byWindow.edgeCalls).hasSize(4)
    }

    @Test
    fun `pages cut inside a millisecond whose trades are out of sequence order still read every trade once`() {
        val slice = Tape(trades("trades-history-mainnet-slice.json"))
        val first = slice.trades.minOf { it.timestampMs }

        val marks =
            DeribitMarks.sampled(
                slice,
                "BTC_USDC-PERPETUAL",
                1L,
                first,
                slice.trades.maxOf { it.timestampMs } + 1,
                perCall = 9,
            )

        val last = slice.trades.groupBy { it.timestampMs }.map { (_, inMs) -> inMs.maxBy { it.seq } }
        assertThat(marks.map { it.timeMs to it.mark }).isEqualTo(last.map { it.timestampMs to it.mark })
        assertThat(slice.read).isEqualTo(slice.trades.map { it.seq }.toSet())
        assertThat(slice.pageCalls.map { it.first }).isSorted.hasSize(3)
    }

    @Test
    fun `a millisecond holding a whole page cannot be paged and fails rather than loop`() {
        val slice = Tape(trades("trades-history-mainnet-slice.json"))
        val first = slice.trades.minOf { it.timestampMs }

        assertThatThrownBy {
            DeribitMarks.sampled(
                slice,
                "BTC_USDC-PERPETUAL",
                1L,
                first,
                slice.trades.maxOf { it.timestampMs } + 1,
                perCall = 2,
            )
        }.hasMessageContaining("cannot be paged")
    }

    @Test
    fun `a range without a trade has no samples and costs one call`() {
        val marks = DeribitMarks.sampled(tape, "BTC_USDC-PERPETUAL", minute, from - 10 * minute, from)

        assertThat(marks).isEmpty()
        assertThat(tape.edgeCalls).hasSize(1)
    }

    @Test
    fun `windows shorter than the gaps between trades sample only the windows that traded`() {
        val second = 1_000L

        val marks = DeribitMarks.sampled(tape, "BTC_USDC-PERPETUAL", second, from, from + 3 * minute)

        val traded = tape.trades.map { it.timestampMs / second }.distinct()
        assertThat(marks.map { it.timeMs / second }).containsExactlyElementsOf(traded)
        assertThat(marks.size).isLessThan(180)
    }

    @Test
    fun `the adapter serves marks from its trade history host, never its trading host`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = ScriptedDeribit(order()).adapter(dir, unusedMarket(), history = tape)

        val marks = adapter.marks("BTC_USDC-PERPETUAL", minute, from, from + 3 * minute)

        assertThat(marks.map { it.mark!!.toPlainString() }).containsExactly("86482.3", "86440.68", "86412.98")
        adapter.close()
    }

    private fun order() =
        DeribitPrivateJson.order(
            Json
                .parseToJsonElement(javaClass.getResource("/fixtures/private/buy-limit-open.json")!!.readText())
                .jsonObject["response"]!!
                .jsonObject["result"]!!
                .jsonObject["order"]!!
                .jsonObject,
        )

    /**
     * Recorded trades answering as Deribit does: edges by time (inclusive) and pages in time order (as
     * recorded within a millisecond), cut at the count asked, saying whether more follow.
     */
    private class Tape(
        val trades: List<DeribitMarkTrade>,
    ) : DeribitMarketData {
        val edgeCalls = mutableListOf<Pair<Long, Long>>()
        val pageCalls = mutableListOf<Pair<Long, Long>>()
        val read = mutableSetOf<Long>()

        override fun edgeTrade(
            name: String,
            fromMs: Long,
            toMs: Long,
            newest: Boolean,
        ): DeribitMarkTrade? {
            edgeCalls += fromMs to toMs
            val inRange = trades.filter { it.timestampMs in fromMs..toMs }
            return if (newest) inRange.maxByOrNull { it.seq } else inRange.minByOrNull { it.seq }
        }

        override fun tradesFrom(
            name: String,
            fromMs: Long,
            toMs: Long,
            count: Int,
        ): DeribitPage<DeribitMarkTrade> {
            pageCalls += fromMs to toMs
            val inRange = trades.filter { it.timestampMs in fromMs..toMs }
            return DeribitPage(inRange.take(count).also { page -> read += page.map { it.seq } }, inRange.size > count)
        }

        override fun instruments(
            currency: String,
            kind: String,
        ): List<DeribitInstrument> = error("not read")

        override fun instrument(name: String): DeribitInstrument = error("not read")

        override fun ticker(name: String) = error("not read")

        override fun klines(
            name: String,
            minutes: Long,
            fromMs: Long,
            toMs: Long,
        ): List<DeribitKline> = error("not read")

        override fun deliveryPrices(
            index: String,
            count: Int,
        ): List<Pair<LocalDate, BigDecimal>> = error("not read")
    }
}
