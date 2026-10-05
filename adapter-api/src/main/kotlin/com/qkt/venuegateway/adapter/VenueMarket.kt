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

/**
 * The mark and index price of a contract as the venue reported them at [timeMs]: the price it values
 * positions and liquidates at, and the spot index it tracks. Either is null when the venue did not report it.
 */
data class VenueMark(
    val timeMs: Long,
    val mark: BigDecimal?,
    val index: BigDecimal?,
)
