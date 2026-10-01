package com.qkt.venued.deribit

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

/** Deribit answered a JSON-RPC error. */
class DeribitException(
    val code: Int,
    message: String,
) : RuntimeException("deribit error $code: $message")
