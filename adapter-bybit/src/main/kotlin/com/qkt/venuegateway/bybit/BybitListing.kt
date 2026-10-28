package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.bybit.client.BybitInstrument
import com.qkt.venuegateway.support.Listing

/**
 * The instruments this account trades: those of its category [read] answers that take orders (`Trading`) and
 * settle in, or for a spot pair are quoted in, [currency]. Read again once [refreshMs] old, and on every lookup of
 * a code it does not hold (Bybit lists new contracts and expiries as it goes) ([Listing]).
 */
internal class BybitListing(
    read: () -> List<BybitInstrument>,
    currency: String,
    clock: () -> Long,
    refreshMs: Long,
) {
    private val listing =
        Listing(
            { read().filter { it.status == "Trading" && it.settleCoin == currency } },
            BybitInstrument::symbol,
            clock,
            refreshMs,
        )

    /** Every instrument listed now. */
    fun all(): List<BybitInstrument> = listing.all().values.sortedBy { it.symbol }

    /** [symbol] as listed now, or null when this account does not trade it. */
    fun find(symbol: String): BybitInstrument? = listing.find(symbol)
}
