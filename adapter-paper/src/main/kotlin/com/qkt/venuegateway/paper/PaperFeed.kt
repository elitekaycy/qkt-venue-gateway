package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.daemon
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.DeribitTickers
import com.qkt.venuegateway.deribit.market.DeribitMarketMapping
import com.qkt.venuegateway.support.Strand
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.slf4j.LoggerFactory

/**
 * The paper venue's prices: the latest ticker of every instrument it hears, and one live subscription
 * covering the codes clients want (the host expands roots into their options) plus what the account
 * trades (working orders and positions, from [trading]), so matching and marks never go blind. The
 * subscription is renewed every [refreshMs], so it follows what the account comes to trade; a renewal that
 * fails is logged and the next one tries again.
 */
class PaperFeed(
    private val refreshMs: Long = 600_000,
    scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon("paper-feed")),
    private val trading: () -> Collection<String>,
) {
    private val log = LoggerFactory.getLogger(PaperFeed::class.java)
    private val timer = Strand(scheduler, "paper-feed")
    private val latest = ConcurrentHashMap<String, DeribitTicker>()
    private var tickers: DeribitTickers? = null
    private var codes = emptySet<String>()

    /** Opens [feed] and subscribes what is wanted. */
    fun start(feed: DeribitTickers) {
        tickers = feed.also { it.start() }
        resubscribe()
        timer.every(refreshMs, refreshMs, ::renew)
    }

    /** The latest ticker of [name], or null before any. */
    operator fun get(name: String): DeribitTicker? = latest[name]

    /** Records [ticker] as the latest of its instrument and returns it as a quote. */
    fun heard(ticker: DeribitTicker): VenueQuote {
        latest[ticker.name] = ticker
        return DeribitMarketMapping.quote(ticker)
    }

    /** Clients want [codes] from now on. */
    fun want(codes: Set<String>) {
        synchronized(this) { this.codes = codes }
        resubscribe()
    }

    /** Subscribes the union of what clients want and what the account trades. */
    fun resubscribe() {
        val wanted = synchronized(this) { codes }
        tickers?.subscribe(wanted + trading())
    }

    fun close() {
        timer.close()
        tickers?.close()
    }

    private fun renew() {
        runCatching { resubscribe() }.onFailure { log.warn("paper feed renewal failed: {}", it.message) }
    }
}
