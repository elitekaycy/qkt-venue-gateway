package com.qkt.venued.host.orders

import com.qkt.venued.adapter.NewOrder
import com.qkt.venued.adapter.Side
import com.qkt.venued.adapter.VenueAdapter
import com.qkt.venued.host.journal.Journal
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
    /** Why [order] may not be sent, or null when it may. */
    fun refusal(order: NewOrder): String? {
        val scope = journal.killSwitch()
        if (!scope.all && order.symbol !in scope.symbols) return null
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
