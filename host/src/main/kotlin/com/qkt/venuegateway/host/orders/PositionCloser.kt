package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.vgp.WireClose
import com.qkt.vgp.WireSubmit
import java.util.concurrent.atomic.AtomicLong

/**
 * `POST /v1/positions/close` (wire spec §3): a reduce-only market order for the other side of the
 * account's live position in a symbol, all of it or part, sent through the [desk] like any submit
 * (written ahead, journaled) but never gated by the kill switch, since flattening is always allowed.
 * Its `client_order_id` is `close.<time>.<n>`, from the gateway's [clock]. Closing one ticket needs a
 * hedging account and an adapter that can close a ticket, which v1's adapter interface has not; it is
 * refused.
 */
class PositionCloser(
    private val desk: OrderDesk,
    private val venue: VenueAdapter,
    private val clock: () -> Long,
) {
    private val sequence = AtomicLong()

    fun close(request: WireClose): DeskResult {
        if (request.ticket != null) return invalid("closing one ticket is not supported; close by symbol")
        val symbol = request.symbol ?: return invalid("symbol is required")
        val positions =
            try {
                venue.positions()
            } catch (e: VenueUnavailableException) {
                return DeskResult.Refused(503, "venue_unavailable", e.message ?: "the venue did not answer")
            }
        if (positions.accounting != Accounting.NETTING) return invalid("this account holds positions by ticket")
        val held = positions.rows.filter { it.symbol == symbol }.sumOf { it.quantity }
        if (held.signum() == 0) return DeskResult.Refused(404, "not_found", "no position in $symbol")
        val asked = request.quantity?.toBigDecimalOrNull()
        if (request.quantity != null &&
            (asked == null || asked.signum() <= 0)
        ) {
            return invalid("quantity must be a positive decimal")
        }
        val quantity = asked ?: held.abs()
        if (quantity > held.abs()) return invalid("$quantity is more than the ${held.abs()} held in $symbol")
        val side = if (held.signum() > 0) "sell" else "buy"
        val id = "close.${clock().toString(36)}.${sequence.incrementAndGet()}"
        return desk.flatten(
            WireSubmit(id, symbol, side, "market", quantity.toPlainString(), null, null, "gtc", reduceOnly = true),
        )
    }

    private fun invalid(message: String) = DeskResult.Refused(400, "invalid_request", message)
}
