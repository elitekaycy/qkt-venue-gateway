package com.qkt.venuegateway.deribit.client

import com.qkt.venuegateway.support.Batch
import com.qkt.venuegateway.support.Paging

/**
 * Walks Deribit's trade history, which pages by time: each page starts at the last trade's timestamp
 * (so trades sharing that millisecond are not skipped) and repeats are dropped by trade id. A page
 * that cannot advance (a full page of trades in one millisecond) fails rather than loop or lose trades.
 */
internal object DeribitTradeHistory {
    fun collect(
        fromMs: Long,
        toMs: Long,
        page: (startMs: Long) -> DeribitPage<DeribitTrade>,
    ): List<DeribitTrade> =
        Paging.whileMore(
            fromMs,
            toMs + 1,
            { start -> page(start).let { Batch(it.items, it.more) } },
            DeribitTrade::tradeId,
            DeribitTrade::timestampMs,
            "deribit trades",
        )
}
