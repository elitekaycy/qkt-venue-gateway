package com.qkt.venuegateway.deribit.market

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.deribit.client.DERIBIT_KLINE_MINUTES
import com.qkt.venuegateway.deribit.client.DeribitFundingRate
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitOrderBook
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal

/**
 * Where Deribit's market words become the gateway's neutral types. A linear (USDC/USDT-settled)
 * contract's amount is in its base coin, so one unit of quantity is one coin: every instrument is
 * listed with contract size (the P&L multiplier) 1. Deribit takes amounts in multiples of
 * `min_trade_amount`, which is both the volume step and the minimum; its `contract_size` can be coarser
 * (a USDC option's is 1 with a minimum of 0.01), so it is not the step.
 */
object DeribitMarketMapping {
    private const val MINUTE_MS = 60_000L

    /** The kline lengths Deribit serves, in milliseconds. */
    val klineResolutions: Set<Long> = DERIBIT_KLINE_MINUTES.map { it * MINUTE_MS }.toSet()

    /**
     * [code]'s klines [resolutionMs] long starting in `[fromMs, toMs)` on [market], as bars, oldest first. An empty
     * range still asks Deribit (for the kline holding [fromMs]), so an unknown code or an outage reads as for any other.
     */
    fun klines(
        market: DeribitMarketData,
        code: String,
        resolutionMs: Long,
        fromMs: Long,
        toMs: Long,
    ) = market
        .klines(code, resolutionMs / MINUTE_MS, fromMs, maxOf(fromMs, toMs - 1))
        .filter { it.startMs in fromMs until toMs }
        .map { VenueBar(it.startMs, it.open, it.high, it.low, it.close, it.volume) }

    /** One hour of Deribit funding: a unit long paid `interest_1h × index` over the hour ending at its time. */
    fun fundingRate(r: DeribitFundingRate) = VenueFundingRate(r.timestampMs, r.interest1h, r.indexPrice)

    /** [t]'s open interest, known at the ticker's own time; null when the ticker carries none. */
    fun openInterest(t: DeribitTicker) = t.openInterest?.let { VenueOpenInterest(t.timestampMs, it) }

    /** [b] as stamped, its best [VenueDepth.MAX_LEVELS] levels a side, amounts in the contract's order quantity. */
    fun depth(b: DeribitOrderBook): VenueDepth {
        fun side(levels: List<Pair<BigDecimal, BigDecimal>>) =
            levels.take(VenueDepth.MAX_LEVELS).map { (price, amount) -> VenueLevel(price, amount) }
        return VenueDepth(b.timestampMs, side(b.bids), side(b.asks))
    }

    fun instrument(i: DeribitInstrument) =
        Instrument(
            code = i.name,
            kind =
                when {
                    i.kind == "option" -> InstrumentKind.OPTION
                    i.perpetual -> InstrumentKind.PERPETUAL
                    else -> InstrumentKind.FUTURE
                },
            currency = i.settlementCurrency,
            contractSize = BigDecimal.ONE,
            tickSize = i.tickSize,
            volumeStep = i.minTradeAmount,
            volumeMin = i.minTradeAmount,
            expiryMs = i.expiryMs,
            strike = i.strike,
            right = i.optionType,
            underlying = if (i.kind == "option") root(i.name) else null,
        )

    /** The root an instrument [name] belongs to, its first field (`BTC_USDC-9OCT26-82000-P` → `BTC_USDC`). */
    fun root(name: String): String = name.substringBefore('-')

    /** [t] as a quote; a side Deribit did not quote stays null. */
    fun quote(t: DeribitTicker) =
        VenueQuote(
            t.name,
            t.bid,
            t.ask,
            t.bidAmount,
            t.askAmount,
            t.mark,
            t.markIv,
            t.underlyingPrice,
            t.timestampMs,
            t.indexPrice,
        )
}
