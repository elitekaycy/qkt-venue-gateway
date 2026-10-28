package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.recording.RecordedDepth
import com.qkt.venuegateway.bybit.BybitErrors.venue
import com.qkt.venuegateway.bybit.client.BybitBook
import java.nio.file.Path

/**
 * Bybit's order book as this gateway recorded it ([RecordedDepth]): Bybit publishes no book history, so a read
 * reaching the present records [book] as it stands (its best [VenueDepth.MAX_LEVELS] levels a side) at Bybit's own
 * stamp. Recorded under `depth/` in [stateDir], its files without a header line (as Bybit's were first written), kept
 * [retentionDays] days.
 */
internal class BybitDepth(
    private val book: (code: String) -> BybitBook,
    stateDir: Path,
    clock: () -> Long,
    retentionDays: Int,
) {
    private val recorded = RecordedDepth(stateDir, headed = false, clock, retentionDays)

    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueDepth> = recorded.read(code, fromMs, toMs) { BybitMarketMapping.depth(venue { book(code) }) }

    /** Deletes the day files past their retention, at most once a UTC day. */
    fun prune() = recorded.prune()
}
