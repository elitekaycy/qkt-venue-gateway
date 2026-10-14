package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.journal.killSwitch
import java.math.BigDecimal

/**
 * The gateway's kill switch on the order path: while a scope (all, or the order's symbol) is on, an
 * order passes only when it is `reduce_only` and reduces the account's position at the venue, read
 * live, by no more than it holds. The scope lives in the [journal], so a restart keeps it.
 */
class KillSwitchGate(
    private val journal: Journal,
    private val venue: VenueAdapter,
) {
    /** Whether a kill scope covers [symbol] now. */
    fun covers(symbol: String): Boolean = journal.killSwitch().let { it.all || symbol in it.symbols }

    /** Why [order] may not be sent, or null when it may. */
    fun refusal(order: NewOrder): String? {
        if (!covers(order.symbol)) return null
        val held =
            venue
                .positions()
                .rows
                .filter { it.symbol == order.symbol }
                .fold(BigDecimal.ZERO) { sum, row -> sum.add(row.quantity) }
        val reduces =
            order.reduceOnly &&
                order.quantity <= held.abs() &&
                ((order.side == Side.SELL && held.signum() > 0) || (order.side == Side.BUY && held.signum() < 0))
        return if (reduces) null else "the kill switch covers ${order.symbol}; only orders that reduce the account pass"
    }
}
