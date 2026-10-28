package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.recording.RecordedOpenInterest
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.nio.file.Path

/**
 * Deribit's open interest as this gateway recorded it ([RecordedOpenInterest]): Deribit publishes no open-interest
 * history, only the present figure on an instrument's [ticker], so a read reaching the present records the ticker's
 * figure at the ticker's time. The record is `open-interest/<code>/<yyyy-MM-dd>.csv` under [stateDir], kept
 * [retentionDays] days (0 for ever; [prune]); an older gateway's single `open-interest/<code>.csv` is split into day
 * files on the code's first read. A code that is not a Deribit instrument name is refused before anything is read or
 * written. [ticker] reads an instrument's ticker, its failures the adapter's own. Shared by every adapter quoting
 * Deribit's public market.
 */
class DeribitOpenInterest(
    private val ticker: (code: String) -> DeribitTicker,
    stateDir: Path,
    clock: () -> Long,
    retentionDays: Int = 0,
) {
    private val recorded = RecordedOpenInterest(stateDir, clock, retentionDays)

    /** [code]'s recorded figures from [fromMs] to [toMs], oldest first, after recording the present one when the window reaches it. */
    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueOpenInterest> {
        if (!CODE.matches(code)) throw VenueRefusedException("$code is not a Deribit instrument name")
        return recorded.read(code, fromMs, toMs) { DeribitMarketMapping.openInterest(ticker(code)) }
    }

    /** Deletes the day files older than the retention window, at most once a UTC day; returns how many. */
    fun prune(): Int = recorded.prune()

    private companion object {
        val CODE = Regex("[A-Za-z0-9_-]+")
    }
}
