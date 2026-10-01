package com.qkt.venuegateway.paper

import com.qkt.venuegateway.deribit.DeribitMapping
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import java.util.concurrent.ConcurrentHashMap

/**
 * The instruments the paper venue trades: Deribit's live [currency] perpetuals, futures and options,
 * re-read at most every [refreshMs], listed to clients through [DeribitMapping.instrument]. A contract
 * that expired leaves the live listing but is still looked up one by one ([held]), so a held position
 * settles.
 */
class PaperListing(
    private val market: DeribitMarketData,
    private val currency: String,
    private val clock: () -> Long,
    private val refreshMs: Long = 600_000,
) {
    @Volatile private var byName: Map<String, DeribitInstrument> = emptyMap()

    @Volatile private var readAt: Long? = null
    private val archived = ConcurrentHashMap<String, DeribitInstrument>()

    /** Every listed instrument, refreshed when due. */
    fun all(): Collection<DeribitInstrument> = current().values

    /** [name]'s listing, re-reading when it is not known (a contract listed since), at most once a minute. */
    fun find(name: String): DeribitInstrument? {
        current()[name]?.let { return it }
        archived[name]?.let { return it }
        val last = readAt
        return if (last != null && clock() - last < MISS_RELOAD_MS) null else reload()[name]
    }

    /** [name]'s listing even after it expired and left the live listing (a held contract to settle). */
    fun held(name: String): DeribitInstrument = find(name) ?: archived.getOrPut(name) { market.instrument(name) }

    /** The listed options of [root]. */
    fun optionsOf(root: String): List<String> =
        all().filter { it.kind == "option" && DeribitMapping.root(it.name) == root }.map { it.name }

    private companion object {
        const val MISS_RELOAD_MS = 60_000L
    }

    private fun current(): Map<String, DeribitInstrument> {
        val last = readAt
        return if (last == null || clock() - last >= refreshMs) reload() else byName
    }

    @Synchronized
    private fun reload(): Map<String, DeribitInstrument> {
        byName =
            (market.instruments(currency, "future") + market.instruments(currency, "option")).associateBy { it.name }
        readAt = clock()
        return byName
    }
}
