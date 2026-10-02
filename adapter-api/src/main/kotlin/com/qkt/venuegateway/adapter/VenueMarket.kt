package com.qkt.venuegateway.adapter

import java.math.BigDecimal

/**
 * A quote of [symbol] as of [timeMs]; any side or value the venue did not report is null. [markIv] is in
 * vol points; [underlying] is the price the contract is valued against, [index] the spot index the venue
 * charges fees on.
 */
data class VenueQuote(
    val symbol: String,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val bidSize: BigDecimal? = null,
    val askSize: BigDecimal? = null,
    val mark: BigDecimal? = null,
    val markIv: BigDecimal? = null,
    val underlying: BigDecimal? = null,
    val timeMs: Long,
    val index: BigDecimal? = null,
)

/** One closed bar starting at [startMs]. */
data class VenueBar(
    val startMs: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
)
