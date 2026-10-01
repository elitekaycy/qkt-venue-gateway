package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.deribit.DeribitListing
import com.qkt.venuegateway.deribit.DeribitMarketMapping
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.util.concurrent.ConcurrentHashMap

/**
 * The paper venue's prices: the latest ticker of every instrument it hears, and one live subscription
 * covering what clients want (codes, and every listed option of each root) plus what the account
 * trades (working orders and positions, from [trading]), so matching and marks never go blind.
 */
class PaperFeed(
    private val listing: DeribitListing,
    private val trading: () -> Collection<String>,
) {
    private val latest = ConcurrentHashMap<String, DeribitTicker>()
    private var tickers: DeribitTickers? = null
    private var codes = emptySet<String>()
    private var roots = emptySet<String>()

    /** Opens [feed] and subscribes what is wanted. */
    fun start(feed: DeribitTickers) {
        tickers = feed.also { it.start() }
        resubscribe()
    }

    /** The latest ticker of [name], or null before any. */
    operator fun get(name: String): DeribitTicker? = latest[name]

    /** Records [ticker] as the latest of its instrument and returns it as a quote. */
    fun heard(ticker: DeribitTicker): VenueQuote {
        latest[ticker.name] = ticker
        return DeribitMarketMapping.quote(ticker)
    }

    /** Clients want [codes] and [roots] from now on. */
    fun want(
        codes: Set<String>,
        roots: Set<String>,
    ) {
        synchronized(this) {
            this.codes = codes
            this.roots = roots
        }
        resubscribe()
    }

    /** Subscribes the union of what clients want and what the account trades. */
    fun resubscribe() {
        val (wantedCodes, wantedRoots) = synchronized(this) { codes to roots }
        tickers?.subscribe(wantedCodes + wantedRoots.flatMap(listing::optionsOf) + trading())
    }

    fun close() {
        tickers?.close()
    }
}
