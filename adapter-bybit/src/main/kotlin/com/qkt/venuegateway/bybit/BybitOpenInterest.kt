package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.bybit.BybitErrors.venue
import com.qkt.venuegateway.bybit.client.BybitPublicClient

/**
 * A contract's open interest from Bybit's own history (`/v5/market/open-interest`, every five minutes, in the
 * base coin). Bybit stamps each figure with the start of its five minutes and publishes it up to a minute later
 * (measured on testnet), so each is served at the end of its five minutes, never before it was known. A window
 * reaching the present also holds the figure the ticker carries now, at Bybit's time.
 */
internal class BybitOpenInterest(
    private val market: BybitPublicClient,
    private val category: String,
    private val clock: () -> Long,
) {
    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueOpenInterest> {
        val now = clock()
        val until = minOf(toMs, now)
        val history =
            venue { market.openInterest(category, code, INTERVAL, fromMs - INTERVAL_MS, until) }
                .map { VenueOpenInterest(it.timeMs + INTERVAL_MS, it.openInterest) }
                .filter { it.timeMs in fromMs..until }
        if (toMs < now - PRESENT_MS) return history
        val present =
            venue { market.ticker(category, code) }?.let { t ->
                t.openInterest?.let { VenueOpenInterest(t.timeMs, it) }
            }
        return if (present != null &&
            present.timeMs > (history.lastOrNull()?.timeMs ?: Long.MIN_VALUE)
        ) {
            history + present
        } else {
            history
        }
    }

    private companion object {
        const val INTERVAL = "5min"
        const val INTERVAL_MS = 300_000L

        /** How close to now a window must end to hold the present figure. */
        const val PRESENT_MS = 60_000L
    }
}
