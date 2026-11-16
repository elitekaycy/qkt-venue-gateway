package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.recording.RecordedDepth
import com.qkt.venuegateway.adapter.recording.RecordedOpenInterest
import com.qkt.venuegateway.kalshi.KalshiErrors.venue
import com.qkt.venuegateway.kalshi.client.KalshiPublic
import com.qkt.venuegateway.support.Paging
import org.slf4j.LoggerFactory

/**
 * What the adapter serves of Kalshi's public market, for listed markets only: klines from Kalshi's candlesticks (1 m,
 * 1 h, 1 d, at most 5,000 a call, so longer ranges are cut into spans), the tape from Kalshi's own trade history
 * (`/markets/trades`, newest first, paged by cursor), and depth and open interest recorded as read
 * ([RecordedDepth], [RecordedOpenInterest]): Kalshi publishes no book history and only the present open interest.
 * Kalshi stamps neither its book nor a market's open interest, so both are recorded at the gateway's clock.
 */
internal class KalshiMarketData(
    private val public: KalshiPublic,
    private val listing: KalshiListing,
    private val context: AdapterContext,
) {
    private val log = LoggerFactory.getLogger(KalshiMarketData::class.java)
    private val clock = context.clock
    private val depth = RecordedDepth(context.stateDir, headed = true, clock, context.recordingRetentionDays)
    private val openInterest = RecordedOpenInterest(context.stateDir, clock, context.recordingRetentionDays)

    fun klines(
        code: String,
        resolutionMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<VenueBar> {
        val series = listed(code).event.seriesTicker
        val minutes = PERIODS[resolutionMs] ?: throw VenueRefusedException("kalshi has no ${resolutionMs}ms candles")
        return Paging.spans(
            fromMs,
            toMs,
            resolutionMs * MAX_CANDLES,
            { from, to ->
                venue { public.candles(series, code, minutes, from + resolutionMs, to + resolutionMs - 1) }
                    .mapNotNull { KalshiMarketMapping.bar(it, resolutionMs) }
                    .filter { it.startMs in from until to }
            },
            VenueBar::startMs,
            VenueBar::startMs,
        )
    }

    /** The first [limit] prints of [code] in `[fromMs, toMs)`, oldest first. */
    fun trades(
        code: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
    ): List<VenuePrint> {
        listed(code)
        return venue { public.trades(code, fromMs, toMs - 1) }
            .map(KalshiMarketMapping::print)
            .filter { it.timeMs in fromMs until toMs }
            .sortedBy { it.timeMs }
            .take(limit)
    }

    fun depth(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueDepth> {
        listed(code)
        pruned()
        return depth.read(code, fromMs, toMs) {
            KalshiMarketMapping.depth(venue { public.book(code, VenueDepth.MAX_LEVELS) }, clock())
        }
    }

    fun openInterest(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueOpenInterest> {
        listed(code)
        pruned()
        return openInterest.read(code, fromMs, toMs) {
            venue { public.market(code) }?.openInterest?.let { VenueOpenInterest(clock(), it) }
        }
    }

    /** The quotes of [codes] as they stand now, first read: Kalshi's ticker pushes only changes. */
    fun snapshot(codes: Collection<String>): List<VenueQuote> =
        venue { public.markets(codes) }.map { KalshiMarketMapping.quote(it, clock()) }

    /** Those of [codes] the listing holds, the rest logged as not quoted. */
    fun quotable(codes: Set<String>): List<String> {
        val (known, unknown) = codes.partition { venue { listing.find(it) } != null }
        if (unknown.isNotEmpty()) log.warn("kalshi lists no {}; not quoted", unknown)
        return known
    }

    private fun listed(code: String): KalshiListed =
        venue { listing.find(code) } ?: throw VenueRefusedException("kalshi lists no market $code")

    private fun pruned() {
        runCatching { depth.prune() + openInterest.prune() }
            .onFailure { log.warn("kalshi recordings not pruned: {}", it.message) }
    }

    companion object {
        /** The candle lengths Kalshi serves, in milliseconds, to its `period_interval` in minutes. */
        val PERIODS = mapOf(60_000L to 1, 3_600_000L to 60, 86_400_000L to 1440)
        private const val MAX_CANDLES = 4_999L
    }
}
