package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.deribit.DeribitDepth
import com.qkt.venuegateway.deribit.DeribitOpenInterest
import com.qkt.venuegateway.deribit.client.DeribitMarketData

/**
 * What the paper venue records of Deribit's market, which Deribit keeps no history of: open interest and the
 * order book ([DeribitOpenInterest], [DeribitDepth]) read from [market], in [context]'s state directory, kept
 * for the host's one retention ([AdapterContext.recordingRetentionDays], 0 for ever).
 */
internal class PaperRecordings(
    market: DeribitMarketData,
    context: AdapterContext,
) {
    /** Open interest as recorded. */
    val openInterest =
        DeribitOpenInterest(
            { venue { market.ticker(it) } },
            context.stateDir,
            context.clock,
            context.recordingRetentionDays,
        )

    /** The order book as recorded. */
    val depth = DeribitDepth(market::orderBook, context.stateDir, context.clock, context.recordingRetentionDays)

    /** Deletes both recordings' day files past their retention, at most once a UTC day; returns how many. */
    fun prune(): Int = depth.prune() + openInterest.prune()
}
