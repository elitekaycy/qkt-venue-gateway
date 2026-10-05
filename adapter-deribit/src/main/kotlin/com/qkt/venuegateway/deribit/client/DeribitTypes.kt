package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal

/**
 * One Deribit instrument as `public/get_instruments` lists it. A perpetual is `kind: future` with
 * `settlement_period: perpetual` (and a placeholder expiry); [expiryMs] is null for it. [active] is
 * `is_active`: Deribit lists contracts that are `inactive` (no ticker is pushed and no order is taken).
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
    val active: Boolean = true,
)

/**
 * One ticker: the best bid and ask with their amounts (Deribit sends `0.0` for a missing side, read
 * here as null), the mark, and for options the mark IV (vol points) and the underlying price. [openInterest] is
 * the contracts outstanding in the instrument's amount unit (the base coin on a linear contract).
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
    val openInterest: BigDecimal? = null,
)

/**
 * One `public/get_order_book` answer: [bids] from the highest price down and [asks] from the lowest up, each
 * level price to amount (the instrument's amount unit, the base coin on a linear contract), as stamped at
 * [timestampMs]. A side with no orders is empty.
 */
data class DeribitOrderBook(
    val name: String,
    val timestampMs: Long,
    val bids: List<Pair<BigDecimal, BigDecimal>>,
    val asks: List<Pair<BigDecimal, BigDecimal>>,
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

/**
 * One print of Deribit's public trade tape: [amount] (coins, on USDC-linear contracts) at [price]. [direction] is
 * the taker's (`buy`, `sell`); [liquidation] is set only on a print that liquidated a position: `T` the taker
 * was liquidated, `M` the maker, `MT` both. [tradeId] is unique; [seq] orders an instrument's trades.
 */
data class DeribitPublicTrade(
    val tradeId: String,
    val seq: Long,
    val timestampMs: Long,
    val price: BigDecimal,
    val amount: BigDecimal,
    val direction: String,
    val liquidation: String?,
)
