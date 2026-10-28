package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.daemon
import com.qkt.venuegateway.deribit.client.DeribitTickers
import com.qkt.venuegateway.support.Strand
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.slf4j.LoggerFactory

/**
 * The tickers clients want, the [codes][want] the host names (it expands roots into their options), on one ticker
 * link. [upkeep] is the adapter's periodic work, run on the feed's timer at [start] and every `refreshMs` after, its
 * failure logged.
 */
class DeribitQuoteFeed(
    private val tickers: DeribitTickers,
    private val upkeep: () -> Unit = {},
    scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon("deribit-refresh")),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(DeribitQuoteFeed::class.java)
    private val timer = Strand(scheduler, "deribit-refresh")

    /** Opens the ticker link; runs [upkeep] now and every [refreshMs]. */
    fun start(refreshMs: Long) {
        tickers.start()
        timer.every(0, refreshMs, ::upkeepLogged)
    }

    /** Clients want [codes] from now on. */
    fun want(codes: Set<String>) = tickers.subscribe(codes)

    private fun upkeepLogged() {
        runCatching(upkeep).onFailure { log.warn("deribit upkeep failed: {}", it.message) }
    }

    override fun close() {
        timer.close()
        tickers.close()
    }
}
