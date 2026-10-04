package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal

/**
 * One Deribit instrument as `public/get_instruments` lists it. A perpetual is `kind: future` with
 * `settlement_period: perpetual` (and a placeholder expiry); [expiryMs] is null for it.
 */
data class DeribitInstrument(
    val name: String,
    val kind: String,
    val perpetual: Boolean,
    val expiryMs: Long?,
    val strike: BigDecimal?,
    val optionType: String?,
    val contractSize: BigDecimal,
    val tickSize: BigDecimal,
    val minTradeAmount: BigDecimal,
    val settlementCurrency: String,
    val priceIndex: String,
)

/**
 * One ticker: the best bid and ask with their amounts (Deribit sends `0.0` for a missing side, read
 * here as null), the mark, and for options the mark IV (vol points) and the underlying price.
 */
data class DeribitTicker(
    val name: String,
    val timestampMs: Long,
    val bid: BigDecimal?,
    val bidAmount: BigDecimal?,
    val ask: BigDecimal?,
    val askAmount: BigDecimal?,
    val mark: BigDecimal?,
    val markIv: BigDecimal?,
    val underlyingPrice: BigDecimal?,
    val indexPrice: BigDecimal?,
)

/** One kline of `public/get_tradingview_chart_data`. */
data class DeribitKline(
    val startMs: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
)

/** Deribit answered a JSON-RPC error [code], with the offending parameter's [reason] when it names one. */
class DeribitException(
    val code: Int,
    message: String,
    val reason: String? = null,
) : RuntimeException("deribit error $code: $message" + (reason?.let { " ($it)" } ?: ""))

/**
 * One hour of a perpetual's funding as `public/get_funding_rate_history` reports it: [interest1h], the
 * rate accrued over the hour ending at [timestampMs], and [indexPrice], the index at its end.
 */
data class DeribitFundingRate(
    val timestampMs: Long,
    val interest1h: BigDecimal,
    val indexPrice: BigDecimal,
)

/**
 * One public trade as Deribit's trade history reports it, reduced to what marks need: its per-instrument
 * sequence number [seq] (later trades have higher numbers), its time, and the [mark] and [index] prices
 * Deribit stamped on it.
 */
data class DeribitMarkTrade(
    val seq: Long,
    val timestampMs: Long,
    val mark: BigDecimal?,
    val index: BigDecimal?,
)
