package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.bybit.client.BybitBook
import com.qkt.venuegateway.bybit.client.BybitFundingRate
import com.qkt.venuegateway.bybit.client.BybitInstrument
import com.qkt.venuegateway.bybit.client.BybitKline
import com.qkt.venuegateway.bybit.client.BybitPrint
import com.qkt.venuegateway.bybit.client.BybitTicker
import java.math.BigDecimal

/**
 * Where Bybit's market words become the gateway's types. A linear contract's quantity is in its base coin (one
 * unit, contract size 1), as is a spot pair's. A Bybit value this mapping does not know fails by name.
 */
internal object BybitMarketMapping {
    fun instrument(i: BybitInstrument): Instrument {
        val kind =
            when {
                i.category == BybitSettings.SPOT -> InstrumentKind.SPOT
                i.contractType == "LinearPerpetual" -> InstrumentKind.PERPETUAL
                i.contractType == "LinearFutures" -> InstrumentKind.FUTURE
                else -> error("bybit contract type ${i.contractType} of ${i.symbol} is not supported")
            }
        return Instrument(
            code = i.symbol,
            kind = kind,
            currency = i.settleCoin,
            contractSize = BigDecimal.ONE,
            tickSize = i.tickSize,
            volumeStep = i.qtyStep,
            volumeMin = i.minOrderQty,
            expiryMs = i.deliveryMs.takeIf { kind == InstrumentKind.FUTURE },
        )
    }

    /** [t] as a quote: a side with no price or a zero size is absent; mark and index only on contracts. */
    fun quote(t: BybitTicker): VenueQuote {
        val (bid, bidSize) = side(t.bid, t.bidSize)
        val (ask, askSize) = side(t.ask, t.askSize)
        return VenueQuote(t.symbol, bid, ask, bidSize, askSize, mark = t.mark, timeMs = t.timeMs, index = t.index)
    }

    fun bar(k: BybitKline) = VenueBar(k.startMs, k.open, k.high, k.low, k.close, k.volume ?: BigDecimal.ZERO)

    /** Bybit publishes the rate, not the price it was applied at. */
    fun fundingRate(r: BybitFundingRate) = VenueFundingRate(r.timeMs, r.rate, null)

    /** A tape print: Bybit's side is the taker's, the aggressor's. */
    fun print(p: BybitPrint) = VenuePrint(p.id, p.timeMs, p.price, p.size, side(p.side))

    /**
     * A liquidation: Bybit names the liquidated position's side (`Buy` a long), and the order that liquidated it
     * is the other side (a long is liquidated by a sale).
     */
    fun liquidation(p: BybitPrint) =
        VenuePrint(
            p.id,
            p.timeMs,
            p.price,
            p.size,
            if (side(p.side) ==
                Side.BUY
            ) {
                Side.SELL
            } else {
                Side.BUY
            },
        )

    fun depth(b: BybitBook) =
        VenueDepth(
            b.timeMs,
            b.bids.take(VenueDepth.MAX_LEVELS).map { VenueLevel(it.first, it.second) },
            b.asks.take(VenueDepth.MAX_LEVELS).map { VenueLevel(it.first, it.second) },
        )

    fun side(bybit: String): Side =
        when (bybit) {
            "Buy" -> Side.BUY
            "Sell" -> Side.SELL
            else -> error("bybit side $bybit is not supported")
        }

    private fun side(
        price: BigDecimal?,
        size: BigDecimal?,
    ): Pair<BigDecimal?, BigDecimal?> =
        if (price == null || size == null || price.signum() == 0 || size.signum() == 0) null to null else price to size
}
