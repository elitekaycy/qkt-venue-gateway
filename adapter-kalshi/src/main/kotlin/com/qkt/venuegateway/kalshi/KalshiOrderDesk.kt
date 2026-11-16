package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.Tradability
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.kalshi.KalshiErrors.venue
import com.qkt.venuegateway.kalshi.client.KalshiOrder
import com.qkt.venuegateway.kalshi.client.KalshiPortfolio
import com.qkt.venuegateway.support.Retry
import java.math.BigDecimal

/**
 * One Kalshi account's orders, each by its label ([KalshiLabels]). A placement is refused before Kalshi for a market
 * this gateway does not trade (unlisted, another shard's, a combo, halted). Kalshi takes `reduce_only` only on an
 * immediate-or-cancel order (recorded: `reduce_only can only be used with IoC orders`), so a resting reduce-only
 * order is sent for at most the position it reduces, and refused when there is none. A label Kalshi already holds
 * (`409 order_already_exists`, a retried placement) answers the order holding it. A placement is read back by its id,
 * retried [readBackTries] times [pauseMs] apart, then reported unavailable so the host resolves it by label. Kalshi
 * shows an amend or a cancel on the order only some time after taking it (measured on the demo: the old price 0.6 s
 * after, the new one at 1 s), so a change returns once the order read by its id shows it, tried [settleTries] times,
 * and is reported unavailable when it never does. A change to what the order already is sends nothing: Kalshi
 * refuses it (`400 AMEND_ORDER_NO_OP`). That wait holds the calling request: about a second as measured, and at
 * most [settleTries] pauses of [pauseMs] (5 s by default) plus the reads. A client whose own call timeout ends first
 * (qkt's default is 5 s) may send the change again, which is safe: the order already shows it, so nothing is sent.
 */
internal class KalshiOrderDesk(
    private val portfolio: KalshiPortfolio,
    private val listing: KalshiListing,
    private val labels: KalshiLabels,
    private val netPosition: (String) -> BigDecimal,
    private val readBackTries: Int = 10,
    private val pauseMs: Long = 200,
    private val settleTries: Int = 25,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    fun place(order: NewOrder): VenueOrder {
        val listed = venue { listing.find(order.symbol) } ?: throw refused("kalshi lists no market ${order.symbol}")
        if (listed.instrument.trading == Tradability.DISABLED) {
            throw refused("${order.symbol} is not traded by this gateway (another shard, a combo, or halted)")
        }
        val immediate = KalshiOrderMapping.immediate(order)
        val sent = if (order.reduceOnly && !immediate) order.copy(quantity = reducible(order)) else order
        val request = KalshiOrderMapping.newOrder(sent, listed.market, order.reduceOnly && immediate)
        labels.sent(order.clientOrderId, KalshiSent(order.type, order.timeInForce, order.reduceOnly))
        val orderId =
            try {
                venue { portfolio.place(request) }.orderId
            } catch (e: VenueRefusedException) {
                if (!e.reason.contains("order_already_exists")) throw e
                return byLabel(order.clientOrderId) ?: throw e
            }
        labels.learn(order.clientOrderId, orderId)
        val read = Retry.until(readBackTries, pauseMs, sleep) { venue { portfolio.order(orderId) } }
        val gone = "kalshi took ${order.clientOrderId} but does not show it yet"
        return read?.let(::mapped) ?: throw VenueUnavailableException(gone)
    }

    /** Cancels the order labelled [label] while Kalshi rests it; an unknown label or an ended order is no error. */
    fun cancel(label: String) {
        val order = find(label) ?: return
        if (order.status != RESTING) return
        try {
            venue { portfolio.cancel(order.orderId) }
        } catch (e: VenueRefusedException) {
            if (!e.reason.contains("not_found")) throw e
            return
        }
        shown(order.orderId, "the cancel of $label") { it.status != RESTING }
    }

    /**
     * Amends the order labelled [label] in price or total quantity, returning once Kalshi shows the change; false
     * when Kalshi has no such order.
     */
    fun modify(
        label: String,
        change: OrderChange,
    ): Boolean {
        if (change.stopPrice != null) throw refused("kalshi has no stop orders: a stop price cannot be set")
        val order = find(label) ?: return false
        if (changed(order, change)) return true
        val count = change.quantity ?: (order.fillCount + order.remainingCount)
        venue { portfolio.amend(order, count, change.limitPrice ?: order.yesPrice) }
        shown(order.orderId, "the change to $label") { changed(it, change) }
        return true
    }

    fun byLabel(label: String): VenueOrder? = find(label)?.let(::mapped)

    /** The orders Kalshi rests for the account. */
    fun open(): List<VenueOrder> =
        venue { portfolio.orders(RESTING) }.onEach(::learn).map(::mapped).filter { it.status == OrderStatus.WORKING }

    /** The account's fills from [fromMs] to [toMs], oldest first, each under its order's label. */
    fun fills(
        fromMs: Long,
        toMs: Long,
    ): List<VenueFill> =
        venue { portfolio.fills(fromMs, toMs) }
            .map { KalshiMarketMapping.fill(it, it.clientOrderId ?: labelOf(it.orderId)) }
            .sortedBy { it.timeMs }

    /** [o] as a VGP order, with what was sent under its label. */
    fun mapped(o: KalshiOrder): VenueOrder = KalshiOrderMapping.order(o, o.clientOrderId?.let(labels::sent))

    /** Learns the label of an order Kalshi reported (a push, a listing). */
    fun learn(o: KalshiOrder) {
        o.clientOrderId?.let { labels.learn(it, o.orderId) }
    }

    private fun find(label: String): KalshiOrder? {
        labels.id(label)?.let { id -> venue { portfolio.order(id) }?.let { return it } }
        val all = venue { portfolio.orders() }
        all.forEach(::learn)
        return all.firstOrNull { it.clientOrderId == label }
    }

    /** Whether [o] already is what [change] asks: the price and the total quantity it names. */
    private fun changed(
        o: KalshiOrder,
        change: OrderChange,
    ): Boolean {
        val priced = change.limitPrice?.let { it.compareTo(o.yesPrice) == 0 } ?: true
        val sized = change.quantity?.let { it.compareTo(o.fillCount + o.remainingCount) == 0 } ?: true
        return priced && sized
    }

    /** Waits until order [orderId], read by its id, is as [done] says; unavailable when it never is. */
    private fun shown(
        orderId: String,
        what: String,
        done: (KalshiOrder) -> Boolean,
    ) {
        Retry.until(settleTries, pauseMs, sleep) { venue { portfolio.order(orderId) }?.takeIf(done) }
            ?: throw VenueUnavailableException("kalshi took $what but does not show it yet")
    }

    private fun labelOf(orderId: String): String =
        labels.label(orderId)
            ?: venue { portfolio.order(orderId) }?.also(::learn)?.clientOrderId
            ?: orderId

    private fun reducible(order: NewOrder): BigDecimal {
        val held = netPosition(order.symbol)
        val reducing = if (order.side == Side.SELL) held.signum() > 0 else held.signum() < 0
        if (!reducing) throw refused("reduce-only ${order.side} of ${order.symbol} with no position to reduce")
        return order.quantity.min(held.abs())
    }

    private fun refused(reason: String) = VenueRefusedException(reason)

    private companion object {
        const val RESTING = "resting"
    }
}
