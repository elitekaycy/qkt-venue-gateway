package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.deribit.client.DeribitMarkTrade
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.TRADES_PER_CALL
import com.qkt.venuegateway.support.Batch
import com.qkt.venuegateway.support.Paging

/**
 * A contract's mark and index per window (wire spec `/v1/marks`) from Deribit's trade history, whose every
 * trade carries the mark and index at its instant: in each window, the last trade's, at its time. Deribit
 * keeps no mark history of futures or perpetuals (`public/get_mark_price_history` answers only some options,
 * and no index), so trades are the record, and a window without a trade has no sample. The range's first
 * and last trades are read, then whichever costs fewer calls: the last trade of each window, or every
 * trade, a thousand at a time in time order (the count between them known from their sequence numbers).
 */
object DeribitMarks {
    /**
     * [code]'s samples in the windows [windowMs] long starting in `[fromMs, toMs)`, oldest first; [perCall] is
     * how many trades one call answers.
     */
    fun sampled(
        history: DeribitMarketData,
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
        perCall: Int = TRADES_PER_CALL,
    ): List<VenueMark> {
        val first = history.edgeTrade(code, fromMs, toMs - 1, newest = false) ?: return emptyList()
        val last = history.edgeTrade(code, fromMs, toMs - 1, newest = true) ?: return emptyList()
        val windows = (last.timestampMs / windowMs - first.timestampMs / windowMs) + 1
        val calls = (last.seq - first.seq) / perCall + 1
        val trades =
            if (calls <= windows) {
                byTime(history, code, first, last, perCall)
            } else {
                byWindow(history, code, windowMs, first, last)
            }
        return trades
            .groupBy { it.timestampMs / windowMs }
            .map { (_, inWindow) -> inWindow.maxBy { it.seq } }
            .sortedBy { it.timestampMs }
            .map { VenueMark(it.timestampMs, it.mark, it.index) }
    }

    /**
     * Every trade from [first]'s to [last]'s, each once, a page of [perCall] at a time in time order. Each page
     * starts at the millisecond the previous ended in (its trades come in any order within it), so none is
     * skipped; a page that cannot advance (a whole page in one millisecond) fails rather than loop.
     */
    private fun byTime(
        history: DeribitMarketData,
        code: String,
        first: DeribitMarkTrade,
        last: DeribitMarkTrade,
        perCall: Int,
    ): List<DeribitMarkTrade> =
        Paging.whileMore(
            first.timestampMs,
            fetch = { start ->
                history.tradesFrom(code, start, last.timestampMs, perCall).let { Batch(it.items, it.more) }
            },
            id = DeribitMarkTrade::seq,
            time = DeribitMarkTrade::timestampMs,
            what = "deribit trades of $code",
            keep = { it.seq in first.seq..last.seq },
        )

    /** One call per window between [first]'s and [last]'s: its last trade (the last window's is [last]). */
    private fun byWindow(
        history: DeribitMarketData,
        code: String,
        windowMs: Long,
        first: DeribitMarkTrade,
        last: DeribitMarkTrade,
    ): List<DeribitMarkTrade> {
        val lastStart = last.timestampMs / windowMs * windowMs
        val starts =
            generateSequence(first.timestampMs / windowMs * windowMs) { it + windowMs }.takeWhile {
                it <
                    lastStart
            }
        return starts.mapNotNull { history.edgeTrade(code, it, it + windowMs - 1, newest = true) }.toList() + last
    }
}
