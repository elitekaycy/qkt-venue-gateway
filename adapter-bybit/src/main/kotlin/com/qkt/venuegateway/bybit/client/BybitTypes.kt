package com.qkt.venuegateway.bybit.client

import java.math.BigDecimal

/**
 * One Bybit instrument as `/v5/market/instruments-info` lists it, in [category] (`linear`, `spot`). [qtyStep]
 * is `lotSizeFilter.qtyStep` (a spot pair's `basePrecision`); [deliveryMs] is set only for a dated contract
 * (`LinearFutures`), whose code ends in its expiry (`BTCUSDT-30OCT26`). [settleCoin] is the coin a contract
 * settles in (a spot pair's quote coin). [status] is `Trading` for a contract that takes orders.
 */
data class BybitInstrument(
    val symbol: String,
    val category: String,
    val contractType: String?,
    val status: String,
    val baseCoin: String,
    val quoteCoin: String,
    val settleCoin: String,
    val tickSize: BigDecimal,
    val qtyStep: BigDecimal,
    val minOrderQty: BigDecimal,
    val deliveryMs: Long?,
)

/**
 * A ticker as the last REST read or socket push left it: the best bid and ask with their sizes, the last
 * trade, and on a contract the mark, the index it tracks and the open interest (in the base coin), as of
 * [timeMs]. A field Bybit did not send is null.
 */
data class BybitTicker(
    val symbol: String,
    val timeMs: Long,
    val bid: BigDecimal? = null,
    val bidSize: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val askSize: BigDecimal? = null,
    val last: BigDecimal? = null,
    val mark: BigDecimal? = null,
    val index: BigDecimal? = null,
    val openInterest: BigDecimal? = null,
)

/** One kline starting at [startMs]; a mark or index kline has no [volume]. */
data class BybitKline(
    val startMs: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal?,
)

/** One settled funding [rate] of a perpetual at [timeMs] (`fundingRateTimestamp`). */
data class BybitFundingRate(
    val timeMs: Long,
    val rate: BigDecimal,
)

/** The open interest (base coin) Bybit stamped at [timeMs], the start of the interval it was taken in. */
data class BybitOpenInterest(
    val timeMs: Long,
    val openInterest: BigDecimal,
)

/**
 * One print of the public tape or the liquidation feed: [size] at [price] at [timeMs]. On the tape [side] is
 * the taker's (`Buy` lifted an offer); on the liquidation feed it is the liquidated position's (`Buy`: a long).
 */
data class BybitPrint(
    val id: String,
    val timeMs: Long,
    val price: BigDecimal,
    val size: BigDecimal,
    val side: String,
)

/** The order book at [timeMs] (Bybit's `ts`): bids best first, asks best first, each a price and a size. */
data class BybitBook(
    val timeMs: Long,
    val bids: List<Pair<BigDecimal, BigDecimal>>,
    val asks: List<Pair<BigDecimal, BigDecimal>>,
)
