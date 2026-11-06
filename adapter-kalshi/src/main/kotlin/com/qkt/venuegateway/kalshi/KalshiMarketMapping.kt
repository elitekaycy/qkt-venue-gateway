package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.Cost
import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.kalshi.client.KalshiBook
import com.qkt.venuegateway.kalshi.client.KalshiCandle
import com.qkt.venuegateway.kalshi.client.KalshiFill
import com.qkt.venuegateway.kalshi.client.KalshiMarket
import com.qkt.venuegateway.kalshi.client.KalshiPosition
import com.qkt.venuegateway.kalshi.client.KalshiSettlement
import com.qkt.venuegateway.kalshi.client.KalshiTicker
import com.qkt.venuegateway.kalshi.client.KalshiTrade
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Kalshi's YES/NO market as one YES book. A NO bid at p is a YES ask at 1 − p; Kalshi reports an empty YES bid as
 * 0 and an empty YES ask as 1 (each with size 0), and either is an absent side here. Every price is YES's, in USD.
 */
internal object KalshiMarketMapping {
    private const val USD = "USD"
    private val TWO = BigDecimal(2)

    fun quote(t: KalshiTicker) = quote(t.ticker, t.yesBid, t.yesBidSize, t.yesAsk, t.yesAskSize, t.timeMs)

    fun quote(
        m: KalshiMarket,
        timeMs: Long,
    ) = quote(m.ticker, m.yesBid, m.yesBidSize, m.yesAsk, m.yesAskSize, timeMs)

    /** [book]'s best [VenueDepth.MAX_LEVELS] levels a side at [timeMs]: YES bids high to low, NO bids as YES asks. */
    fun depth(
        book: KalshiBook,
        timeMs: Long,
    ): VenueDepth {
        val bids = book.yes.sortedByDescending { it.first }.map { VenueLevel(it.first, it.second) }
        val asks = book.no.sortedByDescending { it.first }.map { VenueLevel(BigDecimal.ONE - it.first, it.second) }
        return VenueDepth(timeMs, bids.take(VenueDepth.MAX_LEVELS), asks.take(VenueDepth.MAX_LEVELS))
    }

    /**
     * A candle of [periodMs] as a bar of the YES mid (never the last trade, so bars agree with quotes): open and
     * close the mids of the bid's and ask's, high and low the mids of their highs and lows. Null when either side
     * was empty at any of the four (Kalshi's 0 bid or 1 ask).
     */
    fun bar(
        c: KalshiCandle,
        periodMs: Long,
    ): VenueBar? {
        val bid = listOf(c.yesBid.open, c.yesBid.high, c.yesBid.low, c.yesBid.close)
        val ask = listOf(c.yesAsk.open, c.yesAsk.high, c.yesAsk.low, c.yesAsk.close)
        if (bid.any { it == null || it.signum() <= 0 } || ask.any { it == null || it >= BigDecimal.ONE }) return null
        val mids = bid.zip(ask) { b, a -> (b!! + a!!).divide(TWO) }
        return VenueBar(c.endMs - periodMs, mids[0], mids[1], mids[2], mids[3], c.volume)
    }

    /** A public trade, its side the taker's: a taker buying YES (`bid`) is a buy, one buying NO (`ask`) a sell. */
    fun print(t: KalshiTrade) = VenuePrint(t.tradeId, t.timeMs, t.yesPrice, t.count, side(t.takerBookSide))

    /** One of the account's fills under [label], its fee an exchange fee in USD. */
    fun fill(
        f: KalshiFill,
        label: String,
    ) = VenueFill(
        clientOrderId = label,
        venueOrderId = f.orderId,
        fillId = f.fillId,
        symbol = f.ticker,
        side = side(f.bookSide),
        quantity = f.count,
        price = f.yesPrice,
        timeMs = f.timeMs,
        costs = if (f.fee.signum() == 0) emptyList() else listOf(Cost(CostKind.EXCHANGE_FEE, f.fee, USD)),
    )

    /**
     * A settlement of a market the account held, at Kalshi's settled time (stable, so read twice it is one record):
     * 1 for `yes`, 0 for `no`, and a scalar's value per YES contract (Kalshi's cents) for `scalar`; a fee is a
     * delivery fee. Null for a settlement without a result.
     */
    fun settlement(s: KalshiSettlement): VenueSettlement? {
        val price =
            when (s.marketResult) {
                "yes" -> BigDecimal.ONE
                "no" -> BigDecimal.ZERO
                "scalar" ->
                    BigDecimal(s.valueCents ?: error("kalshi scalar settlement of ${s.ticker} without a value"))
                        .movePointLeft(2)
                "" -> return null
                else -> error("kalshi market_result ${s.marketResult} of ${s.ticker} is not known")
            }
        val costs = if (s.feeCost.signum() == 0) emptyList() else listOf(Cost(CostKind.DELIVERY_FEE, s.feeCost, USD))
        return VenueSettlement(s.ticker, price, s.settledMs, costs)
    }

    /** A position as signed YES contracts at the YES price paid: a NO position's cost buys NO at 1 − p. */
    fun position(p: KalshiPosition): PositionRow? {
        if (p.position.signum() == 0) return null
        val each = p.exposure.divide(p.position.abs(), AVERAGE_DECIMALS, RoundingMode.HALF_EVEN)
        val yes = if (p.position.signum() > 0) each else BigDecimal.ONE - each
        return PositionRow(p.ticker, p.position, yes.stripTrailingZeros())
    }

    fun side(bookSide: String): Side =
        when (bookSide) {
            "bid" -> Side.BUY
            "ask" -> Side.SELL
            else -> error("kalshi book side $bookSide is not known")
        }

    private fun quote(
        ticker: String,
        bid: BigDecimal?,
        bidSize: BigDecimal?,
        ask: BigDecimal?,
        askSize: BigDecimal?,
        timeMs: Long,
    ): VenueQuote {
        val hasBid = bid != null && bid.signum() > 0 && (bidSize == null || bidSize.signum() > 0)
        val hasAsk = ask != null && ask < BigDecimal.ONE && (askSize == null || askSize.signum() > 0)
        return VenueQuote(
            symbol = ticker,
            bid = bid.takeIf { hasBid },
            ask = ask.takeIf { hasAsk },
            bidSize = bidSize.takeIf { hasBid },
            askSize = askSize.takeIf { hasAsk },
            timeMs = timeMs,
        )
    }

    private const val AVERAGE_DECIMALS = 6
}
