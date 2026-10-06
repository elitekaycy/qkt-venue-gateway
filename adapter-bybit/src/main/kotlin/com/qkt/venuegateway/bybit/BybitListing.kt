package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.bybit.client.BybitInstrument

/**
 * The instruments this account trades: those of its category [read] answers that take orders (`Trading`) and
 * settle in, or for a spot pair are quoted in, [currency]. Read again once [refreshMs] old, and on a lookup of
 * a code it does not hold (Bybit lists new contracts and expiries as it goes).
 */
internal class BybitListing(
    private val read: () -> List<BybitInstrument>,
    private val currency: String,
    private val clock: () -> Long,
    private val refreshMs: Long,
) {
    private var held: Map<String, BybitInstrument> = emptyMap()
    private var readAtMs: Long? = null

    /** Every instrument listed now. */
    fun all(): List<BybitInstrument> = current().values.sortedBy { it.symbol }

    /** [symbol] as listed now, or null when this account does not trade it. */
    fun find(symbol: String): BybitInstrument? = current()[symbol] ?: synchronized(this) { reread() }[symbol]

    private fun current(): Map<String, BybitInstrument> =
        synchronized(this) { readAtMs?.takeIf { clock() - it < refreshMs }?.let { held } ?: reread() }

    private fun reread(): Map<String, BybitInstrument> {
        held = read().filter { it.status == "Trading" && it.settleCoin == currency }.associateBy { it.symbol }
        readAtMs = clock()
        return held
    }
}
