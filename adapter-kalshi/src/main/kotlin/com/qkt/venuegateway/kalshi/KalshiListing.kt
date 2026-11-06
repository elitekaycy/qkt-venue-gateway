package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.kalshi.client.KalshiPublic
import com.qkt.venuegateway.kalshi.client.KalshiSeries
import com.qkt.venuegateway.support.Listing
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Every market of every open event, in every category, mapped once a read ([KalshiMapping]). Kalshi lists series,
 * events and markets apart: the series (about 16,000 in one 20 MB answer) are read every [seriesRefreshMs], and a
 * series an event names that the last read did not hold is read by its ticker; the events with their markets (about
 * 7,000 events, 18,000 markets on production on 2026-11-05) are read again once [refreshMs] old and on a lookup of a
 * ticker not held, at most every [missRereadMs]. A market whose terms cannot be mapped (an unknown status or strike
 * type) is logged and left out, so one odd market never empties the listing.
 */
internal class KalshiListing(
    private val public: KalshiPublic,
    private val shard: Int,
    private val clock: () -> Long,
    refreshMs: Long = 300_000,
    missRereadMs: Long = 60_000,
    private val seriesRefreshMs: Long = 21_600_000,
) {
    private val log = LoggerFactory.getLogger(KalshiListing::class.java)
    private val extraSeries = ConcurrentHashMap<String, KalshiSeries>()
    private val seriesList = Listing({ public.series() }, KalshiSeries::ticker, clock, seriesRefreshMs)
    private val listing = Listing(::read, { it.market.ticker }, clock, refreshMs, missRereadMs)

    /** Every listed market, by ticker. */
    fun all(): Collection<KalshiListed> = listing.all().values

    /** [ticker] as listed now, reading the listing again when it does not hold it (at most every miss interval). */
    fun find(ticker: String): KalshiListed? = listing.find(ticker)

    /** [ticker]'s instrument as listed, or read by ticker with its event when the listing no longer holds it. */
    fun instrument(ticker: String): Instrument? {
        listing.cached(ticker)?.let { return it.instrument }
        val market = public.market(ticker) ?: return null
        val event = public.event(market.eventTicker) ?: return null
        return KalshiMapping.instrument(market, event, series(event.seriesTicker), shard)
    }

    private fun read(): List<KalshiListed> {
        val events = public.openEvents()
        return events.flatMap { event ->
            val series = series(event.seriesTicker)
            event.markets.mapNotNull { market ->
                runCatching { KalshiMapping.listed(market, event, series, shard) }
                    .onFailure { log.error("kalshi market {} left out: {}", market.ticker, it.message) }
                    .getOrNull()
            }
        }
    }

    private fun series(ticker: String): KalshiSeries? =
        seriesList.cached(ticker) ?: extraSeries[ticker] ?: public.series(ticker)?.also { extraSeries[ticker] = it }
}
