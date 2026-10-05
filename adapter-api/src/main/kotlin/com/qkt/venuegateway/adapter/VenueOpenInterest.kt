package com.qkt.venuegateway.adapter

import java.math.BigDecimal

/**
 * The open interest of one contract as the venue published it: [openInterest] contracts outstanding, in the
 * contract's order quantity (the unit an order's `quantity` is written in), known from [timeMs] on. [timeMs]
 * is when the venue made the figure known, never earlier: a venue that stamps a figure with the start of the
 * period it summarizes is mapped to that period's end.
 */
data class VenueOpenInterest(
    val timeMs: Long,
    val openInterest: BigDecimal,
)
