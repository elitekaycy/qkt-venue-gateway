package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitTickers

/**
 * The tickers clients want: the [codes][want] they name, and every listed option of each root. A
 * root's options are expanded from the [listing] again on every [refresh], so expiries Deribit lists
 * after the subscription (it lists new ones daily) are quoted too.
 */
class DeribitQuoteFeed(
    private val listing: DeribitListing,
    private val tickers: DeribitTickers,
) : AutoCloseable {
    private var codes = emptySet<String>()
    private var roots = emptySet<String>()

    fun start() = tickers.start()

    /** Clients want [codes] and the options of [roots] from now on. */
    fun want(
        codes: Set<String>,
        roots: Set<String>,
    ) {
        synchronized(this) {
            this.codes = codes
            this.roots = roots
        }
        refresh()
    }

    /** Subscribes what is wanted, with each root's options as listed now. */
    fun refresh() {
        val (wantedCodes, wantedRoots) = synchronized(this) { codes to roots }
        tickers.subscribe(wantedCodes + wantedRoots.flatMap(listing::optionsOf))
    }

    override fun close() = tickers.close()
}
