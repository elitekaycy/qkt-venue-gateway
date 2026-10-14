package com.qkt.venuegateway.host.journal

import com.qkt.vgp.WireOrder
import java.math.BigDecimal

/** Which way an order's reported states may move: forward only. */
internal object OrderProgress {
    private val FINAL = setOf("filled", "cancelled", "rejected")

    /** Whether [next] is older than [held]: back from a final state to working, or less filled. */
    fun regresses(
        held: WireOrder,
        next: WireOrder,
    ): Boolean =
        (held.status in FINAL && next.status !in FINAL) ||
            BigDecimal(next.filledQuantity) < BigDecimal(held.filledQuantity)
}
