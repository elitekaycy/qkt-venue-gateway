package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WireInstrument
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * The listing the gateway serves, read from the [adapter] every [VenueAdapter.listingRefreshMs] and on a lookup
 * miss (at most every [missRereadMs]), never per request: each read goes through the [shelf] (dated contracts kept
 * after the venue drops them) and is indexed by code, and [changes] diffs it with the read before. The first request
 * before any read reads at once (a venue that cannot answer then fails that request). A failed timed read is logged
 * and the last read keeps being served.
 */
class Catalog(
    private val adapter: VenueAdapter,
    private val shelf: InstrumentShelf,
    private val clock: () -> Long,
    private val missRereadMs: Long = 10_000,
    removalKeepMs: Long = 7 * 86_400_000L,
) {
    /** One read of the listing: [listing] in the order served, [byCode], the codes the venue [live] lists, [readAt]. */
    class Read internal constructor(
        val listing: List<WireInstrument>,
        val byCode: Map<String, WireInstrument>,
        val live: Set<String>,
        val readAt: Long,
    )

    private val log = LoggerFactory.getLogger(Catalog::class.java)

    @Volatile private var last: Read? = null

    /** What changed between reads (`updated_since`). */
    internal val changes = CatalogChanges(removalKeepMs)

    /** The latest read, without waiting on a read in progress; reading now when there has been none. */
    fun current(): Read = last ?: refresh()

    /**
     * [code] from the latest read; else, when the last read is at least [missRereadMs] old, from a new read; else as
     * the adapter answers for a code it no longer lists ([VenueAdapter.instrument]); null when none holds it.
     */
    fun find(code: String): WireInstrument? {
        current().byCode[code]?.let { return it }
        reread()?.byCode?.get(code)?.let { return it }
        return adapter.instrument(code)?.let(WireReads::instrument)
    }

    /** Reads the listing now, records what changed and serves it from then on. */
    @Synchronized
    fun refresh(): Read {
        val live = adapter.instruments().map(WireReads::instrument)
        val now = clock()
        val listing = shelf.listing(live)
        val byCode = listing.associateBy { it.code }
        changes.record(byCode, now)
        return Read(listing, byCode, live.mapTo(HashSet()) { it.code }, now).also { last = it }
    }

    /** Reads the listing on [timer] now and every [VenueAdapter.listingRefreshMs]. */
    fun start(timer: ScheduledExecutorService) {
        val period = adapter.listingRefreshMs
        timer.scheduleWithFixedDelay(::refreshQuietly, 0, period, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun reread(): Read? {
        if (clock() - current().readAt < missRereadMs) return null
        return refresh()
    }

    private fun refreshQuietly() {
        runCatching { refresh() }.onFailure { log.warn("instrument listing not read: {}", it.message) }
    }
}
