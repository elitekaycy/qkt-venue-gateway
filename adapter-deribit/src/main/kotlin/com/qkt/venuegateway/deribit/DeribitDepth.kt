package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.recording.RecordedDepth
import com.qkt.venuegateway.deribit.DeribitErrors.venue
import com.qkt.venuegateway.deribit.client.DeribitOrderBook
import java.nio.file.Path

/**
 * Deribit's order book as this gateway recorded it ([RecordedDepth]): Deribit publishes no book history, so a read
 * reaching the present records [book] as it stands (`public/get_order_book` of a code, its best
 * [VenueDepth.MAX_LEVELS] levels a side) at Deribit's own stamp. The record is `depth/<code>/<yyyy-MM-dd>.csv` under
 * [stateDir], each file starting with its `time,bids,asks` header, kept [retentionDays] days (0 for ever; [prune]).
 * A code that is not a Deribit instrument name is refused before anything is read or written. Shared by every
 * adapter quoting Deribit's public market.
 */
class DeribitDepth(
    private val book: (code: String, levels: Int) -> DeribitOrderBook,
    stateDir: Path,
    clock: () -> Long,
    retentionDays: Int = 0,
) {
    private val recorded = RecordedDepth(stateDir, headed = true, clock, retentionDays)

    /** [code]'s recorded snapshots from [fromMs] to [toMs], oldest first, after recording the present one when the window reaches it. */
    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueDepth> {
        if (!CODE.matches(code)) throw VenueRefusedException("$code is not a Deribit instrument name")
        val now = { DeribitMarketMapping.depth(venue { book(code, VenueDepth.MAX_LEVELS) }) }
        return recorded.read(code, fromMs, toMs, now)
    }

    /** Deletes the day files older than the retention window, at most once a UTC day; returns how many. */
    fun prune(): Int = recorded.prune()

    private companion object {
        val CODE = Regex("[A-Za-z0-9_-]+")
    }
}
