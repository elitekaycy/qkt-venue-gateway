package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.support.Listing
import java.util.concurrent.ConcurrentHashMap

/**
 * The instruments a Deribit account trades: the live [currency] perpetuals, futures and options, re-read at most
 * every [refreshMs] ([Listing]), listed to clients through [DeribitMarketMapping.instrument]. A contract that expired
 * leaves the live listing but is still looked up one by one ([held]), so a held position settles. A contract Deribit
 * lists as inactive is never listed (its ticker never quotes), but is still found by name, so one held settles.
 */
class DeribitListing(
    private val market: DeribitMarketData,
    private val currency: String,
    clock: () -> Long,
    refreshMs: Long = 600_000,
) {
    private val live =
        Listing(
            { market.instruments(currency, "future") + market.instruments(currency, "option") },
            DeribitInstrument::name,
            clock,
            refreshMs,
            MISS_RELOAD_MS,
        )
    private val archived = ConcurrentHashMap<String, DeribitInstrument>()

    /** Every active listed instrument, refreshed when due. */
    fun all(): Collection<DeribitInstrument> = live.all().values.filter { it.active }

    /** [name]'s listing, re-reading when it is not known (a contract listed since), at most once a minute. */
    fun find(name: String): DeribitInstrument? = live.cached(name) ?: archived[name] ?: live.find(name)

    /** [name]'s listing even after it expired and left the live listing (a held contract to settle). */
    fun held(name: String): DeribitInstrument = find(name) ?: archived.getOrPut(name) { market.instrument(name) }

    private companion object {
        const val MISS_RELOAD_MS = 60_000L
    }
}
