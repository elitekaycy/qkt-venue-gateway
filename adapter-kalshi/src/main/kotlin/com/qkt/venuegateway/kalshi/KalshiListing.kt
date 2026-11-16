package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentFilter
import com.qkt.venuegateway.adapter.Tradability.DISABLED
import com.qkt.venuegateway.kalshi.client.KalshiEvent
import com.qkt.venuegateway.kalshi.client.KalshiPublic
import com.qkt.venuegateway.kalshi.client.KalshiSeries
import com.qkt.venuegateway.support.Listing
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * The markets of the open events of the series in [scope] (the `series` setting), mapped once a read
 * ([KalshiMapping]). Each named series is read by its ticker and its events through Kalshi's `series_ticker` filter
 * (under a second a series), so nothing outside the scope is ever read; a named series Kalshi does not have fails the
 * read naming it. An empty [scope] is the whole catalogue (`series=*`): every series in one 20 MB answer and every
 * open event page by page, many minutes of reading, so it is kept far longer. The listing is read again once
 * [refreshMs] old and on a lookup of a ticker not held, at most every [missRereadMs]; the series' terms every
 * [seriesRefreshMs]. A market whose terms cannot be mapped (an unknown status or strike type) is logged and left out,
 * so one odd market never empties the listing. [instrument] answers any ticker, listed or not.
 */
internal class KalshiListing(
    private val public: KalshiPublic,
    private val shard: Int,
    private val clock: () -> Long,
    private val scope: List<String>,
    refreshMs: Long = refreshFor(scope),
    missRereadMs: Long = if (scope.isEmpty()) refreshMs else SCOPED_MISS_REREAD_MS,
    private val seriesRefreshMs: Long = 21_600_000,
) {
    private val log = LoggerFactory.getLogger(KalshiListing::class.java)
    private val extraSeries = ConcurrentHashMap<String, KalshiSeries>()
    private val seriesList = Listing(::readSeries, KalshiSeries::ticker, clock, seriesRefreshMs)
    private val listing = Listing(::read, { it.market.ticker }, clock, refreshMs, missRereadMs)

    /** Every listed market, by ticker. */
    fun all(): Collection<KalshiListed> = listing.all().values

    /** [ticker] as listed now, reading the listing again when it does not hold it (at most every miss interval). */
    fun find(ticker: String): KalshiListed? = listing.find(ticker)

    /** [ticker]'s instrument as listed, or read by ticker with its event when the listing does not hold it. */
    fun instrument(ticker: String): Instrument? {
        listing.cached(ticker)?.let { return it.instrument }
        val market = public.market(ticker) ?: return null
        val event = public.event(market.eventTicker) ?: return null
        return traded(KalshiMapping.instrument(market, event, series(event.seriesTicker), shard), event)
    }

    /**
     * The markets of [filter]'s event, or of the open events of its series, read from Kalshi now: how a client finds
     * what the scope does not list. A market outside the scope is `disabled`, as this gateway does not trade it.
     */
    fun search(filter: InstrumentFilter): List<Instrument> {
        val events =
            filter.event?.let { listOfNotNull(public.event(it)) } ?: public.openEvents(requireNotNull(filter.series))
        return events.flatMap { event -> mapped(event).map { traded(it.instrument, event) } }
    }

    private fun traded(
        instrument: Instrument,
        event: KalshiEvent,
    ): Instrument =
        if (scope.isEmpty() || event.seriesTicker in scope) instrument else instrument.copy(trading = DISABLED)

    private fun read(): List<KalshiListed> {
        seriesList.all()
        val events = if (scope.isEmpty()) public.openEvents() else scope.flatMap { public.openEvents(it) }
        return events.flatMap(::mapped)
    }

    private fun mapped(event: KalshiEvent): List<KalshiListed> {
        val series = series(event.seriesTicker)
        return event.markets.mapNotNull { market ->
            runCatching { KalshiMapping.listed(market, event, series, shard) }
                .onFailure { log.error("kalshi market {} left out: {}", market.ticker, it.message) }
                .getOrNull()
        }
    }

    private fun readSeries(): List<KalshiSeries> =
        if (scope.isEmpty()) {
            public.series()
        } else {
            scope.map { public.series(it) ?: error("setting series names $it, a series kalshi does not have") }
        }

    private fun series(ticker: String): KalshiSeries? =
        seriesList.cached(ticker) ?: extraSeries[ticker] ?: public.series(ticker)?.also { extraSeries[ticker] = it }

    companion object {
        private const val SCOPED_REFRESH_MS = 60_000L
        private const val SCOPED_MISS_REREAD_MS = 10_000L
        private const val WHOLE_REFRESH_MS = 3_600_000L

        /** How old a listing of [scope] may grow: a minute for named series, an hour for the whole catalogue. */
        fun refreshFor(scope: List<String>): Long = if (scope.isEmpty()) WHOLE_REFRESH_MS else SCOPED_REFRESH_MS
    }
}
