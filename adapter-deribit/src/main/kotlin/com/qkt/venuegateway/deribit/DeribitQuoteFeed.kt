package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * The tickers clients want: the [codes][want] they name, and every listed option of each root. A
 * root's options are expanded from the [listing] again on every [refresh], so expiries Deribit lists
 * after the subscription (it lists new ones daily) are quoted too, every `refreshMs` once [start]ed; a
 * refresh that fails is logged and tried again at the next. [upkeep] is the adapter's other periodic work, run
 * on the same timer at [start] and every `refreshMs` after, its failure logged.
 */
class DeribitQuoteFeed(
    private val listing: DeribitListing,
    private val tickers: DeribitTickers,
    private val upkeep: () -> Unit = {},
) : AutoCloseable {
    private var codes = emptySet<String>()
    private var roots = emptySet<String>()
    private val log = LoggerFactory.getLogger(DeribitQuoteFeed::class.java)
    private val timer =
        Executors.newSingleThreadScheduledExecutor {
            Thread(it, "deribit-refresh").apply {
                isDaemon =
                    true
            }
        }

    /** Opens the ticker link and refreshes what is wanted every [refreshMs]; runs [upkeep] now and as often. */
    fun start(refreshMs: Long) {
        tickers.start()
        timer.scheduleWithFixedDelay(::refreshLogged, refreshMs, refreshMs, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay(::upkeepLogged, 0, refreshMs, TimeUnit.MILLISECONDS)
    }

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

    private fun refreshLogged() {
        runCatching { refresh() }.onFailure { log.warn("deribit quote refresh failed: {}", it.message) }
    }

    private fun upkeepLogged() {
        runCatching(upkeep).onFailure { log.warn("deribit upkeep failed: {}", it.message) }
    }

    override fun close() {
        timer.shutdownNow()
        tickers.close()
    }
}
