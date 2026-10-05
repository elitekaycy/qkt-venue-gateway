package com.qkt.venuegateway.adapter

import java.math.BigDecimal

/**
 * One snapshot of a contract's order book as the venue stamped it at [timeMs]: at most [MAX_LEVELS] levels a
 * side, [bids] from the highest price down and [asks] from the lowest up, a side the book does not hold
 * empty. [timeMs] is when the venue made the book known, never earlier.
 */
data class VenueDepth(
    val timeMs: Long,
    val bids: List<VenueLevel>,
    val asks: List<VenueLevel>,
) {
    companion object {
        /** The most levels a side a snapshot holds (wire spec `/v1/depth`). */
        const val MAX_LEVELS: Int = 10
    }
}

/** One price level: [amount] resting at [price], in the contract's order quantity (the unit an order's `quantity` is written in). */
data class VenueLevel(
    val price: BigDecimal,
    val amount: BigDecimal,
)
