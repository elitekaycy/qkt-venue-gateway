package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal

/**
 * Where Deribit's market words become the gateway's neutral types. A linear (USDC/USDT-settled)
 * contract's amount is in its base coin, so one unit of quantity is one coin: every instrument is
 * listed with contract size (the P&L multiplier) 1, and Deribit's `contract_size` is the volume step.
 */
object DeribitMarketMapping {
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
            volumeStep = i.contractSize,
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
        VenueQuote(t.name, t.bid, t.ask, t.bidAmount, t.askAmount, t.mark, t.markIv, t.underlyingPrice, t.timestampMs)

    /**
     * The [klines] of [windowMs] starting in `[fromMs, toMs)` that have closed by [nowMs]: Deribit also
     * returns the kline holding `fromMs`, which can start before it, and a last kline still forming.
     */
    fun closedBars(
        klines: List<DeribitKline>,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
        nowMs: Long,
    ): List<VenueBar> =
        klines
            .filter { it.startMs in fromMs until toMs && it.startMs + windowMs <= nowMs }
            .map { VenueBar(it.startMs, it.open, it.high, it.low, it.close, it.volume) }
}
