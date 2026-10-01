package com.qkt.venuegateway.adapter

import java.math.BigDecimal

/** A quote of [symbol] as of [timeMs]; any side or value the venue did not report is null. [markIv] is in vol points. */
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
