package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.recording.RecordedTape
import com.qkt.venuegateway.bybit.BybitErrors.venue
import com.qkt.venuegateway.bybit.client.BYBIT_KLINE_INTERVALS
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import org.slf4j.LoggerFactory

/**
 * What the adapter serves of Bybit's public market beyond quotes, for codes of its [listing] only: klines (the host
 * builds bars from them), marks ([BybitMarks]), funding rates and open interest from Bybit's histories; the tape,
 * liquidations and depth from what the gateway records ([RecordedTape], [BybitDepth]), pruned to their retention at
 * most once a UTC day as they are read. Bybit keeps no history of its tape or liquidations: its REST answers only a
 * contract's latest trades (1000 of a contract, 60 of a spot pair, read before each tape read) and no liquidation at
 * all, so both are taped from the public socket ([tapeCodes]) from the first read of a code on.
 */
internal class BybitMarket(
    private val public: BybitPublicClient,
    private val settings: BybitSettings,
    private val listing: BybitListing,
    private val context: AdapterContext,
    tapeCodes: (Collection<String>) -> Unit,
) {
    private val log = LoggerFactory.getLogger(BybitMarket::class.java)
    private val category = settings.category
    private val marks = BybitMarks(public, category)
    private val openInterest = BybitOpenInterest(public, category, context.clock)
    private val depth =
        BybitDepth({
            public.orderBook(category, it, MAX_BOOK_LEVELS)
        }, context.stateDir, context.clock, context.recordingRetentionDays)

    /** The recorded tape, its pushes fed by the adapter's public socket. */
    val tape = RecordedTape(context.stateDir, context.clock, context.recordingRetentionDays, tapeCodes)

    fun klines(
        code: String,
        resolutionMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<VenueBar> =
        venue {
            val interval = BYBIT_KLINE_INTERVALS.getValue(resolutionMs)
            public.klines(category, listed(code), "kline", interval, resolutionMs, fromMs, toMs - 1)
        }.map(BybitMarketMapping::bar)

    fun marks(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<VenueMark> = venue { marks.sampled(listed(code), windowMs, fromMs, toMs, context.clock()) }

    fun fundingRates(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueFundingRate> =
        venue {
            public.fundingRates(category, listed(code), fromMs, toMs)
        }.map(BybitMarketMapping::fundingRate)

    fun openInterest(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueOpenInterest> = openInterest.read(listed(code), fromMs, toMs)

    fun trades(
        code: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
    ): List<VenuePrint> = pruned { venue { tape.trades(listed(code), fromMs, toMs, limit) { recent(code) } } }

    fun liquidations(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenuePrint> = pruned { venue { tape.liquidations(listed(code), fromMs, toMs) } }

    fun depth(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueDepth> = pruned { depth.read(listed(code), fromMs, toMs) }

    /** Those of [codes] the account lists, the rest logged as not quoted. */
    fun quotable(codes: Set<String>): List<String> {
        val held = venue { listing.all() }.map { it.symbol }.toSet()
        val (known, unknown) = codes.partition { it in held }
        if (unknown.isNotEmpty()) log.warn("bybit {} does not list {}; not quoted", category, unknown)
        return known
    }

    private fun recent(code: String): List<VenuePrint> =
        public.recentTrades(category, code, if (settings.contracts) 1000 else 60).map(BybitMarketMapping::print)

    private fun listed(code: String): String {
        if (venue { listing.find(code) } == null) {
            throw VenueRefusedException("$code is not a listed ${settings.currency} $category instrument")
        }
        return code
    }

    private fun <T> pruned(call: () -> T): T {
        runCatching { tape.prune() + depth.prune() }.onFailure {
            log.warn(
                "bybit recordings not pruned: {}",
                it.message,
            )
        }
        return call()
    }

    private companion object {
        const val MAX_BOOK_LEVELS = 10
    }
}
