package com.qkt.venuegateway.kalshi.client

import java.math.BigDecimal

/** A series: the recurring question its events instance, with its `category`, `frequency` and fee terms. */
data class KalshiSeries(
    val ticker: String,
    val category: String?,
    val frequency: String?,
    val feeType: String?,
    val feeMultiplier: BigDecimal?,
)

/** An event of [seriesTicker] and its [markets]; an event may override its series' fee terms. */
data class KalshiEvent(
    val eventTicker: String,
    val seriesTicker: String,
    val mutuallyExclusive: Boolean,
    val feeTypeOverride: String?,
    val feeMultiplierOverride: BigDecimal?,
    val markets: List<KalshiMarket>,
)

/** One step of a market's price grid: prices from [start] to [end] move in [step]s. */
data class KalshiPriceRange(
    val start: BigDecimal,
    val end: BigDecimal,
    val step: BigDecimal,
)

/** A binary market as Kalshi lists it, prices in dollars and counts in contracts, times in epoch milliseconds. */
data class KalshiMarket(
    val ticker: String,
    val eventTicker: String,
    val status: String,
    val strikeType: String?,
    val floorStrike: BigDecimal?,
    val capStrike: BigDecimal?,
    val openTimeMs: Long,
    val closeTimeMs: Long,
    val expectedExpirationMs: Long?,
    val occurrenceMs: Long?,
    val canCloseEarly: Boolean,
    val yesSubTitle: String?,
    val result: String?,
    val settlementValue: BigDecimal?,
    val priceRanges: List<KalshiPriceRange>,
    val exchangeIndex: Int?,
    val yesBid: BigDecimal?,
    val yesAsk: BigDecimal?,
    val yesBidSize: BigDecimal?,
    val yesAskSize: BigDecimal?,
    val openInterest: BigDecimal?,
    val mveCollection: String?,
)

/** A market's book: resting YES bids and NO bids, each `price to count`, as Kalshi sends them (ascending). */
data class KalshiBook(
    val yes: List<Pair<BigDecimal, BigDecimal>>,
    val no: List<Pair<BigDecimal, BigDecimal>>,
)

/** One public trade; [takerBookSide] `bid` bought YES, `ask` sold YES (bought NO). */
data class KalshiTrade(
    val tradeId: String,
    val ticker: String,
    val count: BigDecimal,
    val yesPrice: BigDecimal,
    val takerBookSide: String,
    val timeMs: Long,
)

/** One OHLC of a candle's YES bid or ask; a side the book did not hold has null prices. */
data class KalshiOhlc(
    val open: BigDecimal?,
    val high: BigDecimal?,
    val low: BigDecimal?,
    val close: BigDecimal?,
)

/** One candlestick ending (inclusive) at [endMs]: the YES bid and ask, the volume and the open interest. */
data class KalshiCandle(
    val endMs: Long,
    val yesBid: KalshiOhlc,
    val yesAsk: KalshiOhlc,
    val volume: BigDecimal,
    val openInterest: BigDecimal?,
)

/** The live ticker of a market: best YES bid and ask with their sizes, at [timeMs]. */
data class KalshiTicker(
    val ticker: String,
    val yesBid: BigDecimal?,
    val yesAsk: BigDecimal?,
    val yesBidSize: BigDecimal?,
    val yesAskSize: BigDecimal?,
    val timeMs: Long,
)
