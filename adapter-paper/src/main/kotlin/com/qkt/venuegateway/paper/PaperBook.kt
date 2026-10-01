package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal

/** What one book change produced: orders that changed and the fills they made. */
data class PaperChange(
    val orders: List<VenueOrder> = emptyList(),
    val fills: List<VenueFill> = emptyList(),
)

/**
 * A paper venue's orders, matched against live tickers at the touch ([touch]). A market order fills
 * whole at the touch and is rejected when that side is not quoted. A limit marketable on arrival fills
 * at the touch; otherwise it rests and fills at its limit once the touch reaches it (IOC and FOK that are
 * not marketable are cancelled). A stop triggers when the touch crosses it and fills at the touch; a
 * stop-limit then behaves as a limit arriving. A `reduce_only` order that would grow or flip the position
 * is refused, on arrival and again when it would fill. Fees are [feeRate] × notional, in [currency].
 * Not thread-safe: the adapter calls it under one lock.
 */
class PaperBook(
    val ledger: PaperLedger,
    val currency: String,
    private val feeRate: BigDecimal,
) {
    /** The orders, fills and settlements this book made. */
    val state = PaperOrders(ledger, currency, feeRate)

    /**
     * Places [order] against [ticker] (the latest, or null when none is known) at [nowMs]. The change
     * always reports the order as it ends up, so an order left working is acknowledged and saved.
     */
    fun place(
        order: NewOrder,
        ticker: DeribitTicker?,
        nowMs: Long,
    ): PaperChange {
        val working = state.newOrder(order, nowMs)
        if (order.reduceOnly) {
            ledger.reduceOnlyRefusal(order.symbol, order.side, order.quantity)?.let {
                return state.end(working, OrderStatus.REJECTED, nowMs, it)
            }
        }
        state.orders[order.clientOrderId] = working
        val matched = match(working, ticker, nowMs, arriving = true)
        return if (matched.orders.isEmpty()) matched.copy(orders = listOf(working)) else matched
    }

    /** Matches every working order of [ticker]'s instrument against it. */
    fun onTicker(
        ticker: DeribitTicker,
        nowMs: Long,
    ): PaperChange =
        state.orders.values
            .filter { it.symbol == ticker.name && it.status == OrderStatus.WORKING }
            .map { match(it, ticker, nowMs, arriving = false) }
            .fold(PaperChange()) { a, b -> PaperChange(a.orders + b.orders, a.fills + b.fills) }

    fun cancel(
        label: String,
        nowMs: Long,
    ): VenueOrder? {
        val order = state.orders[label] ?: return null
        return if (order.status ==
            OrderStatus.WORKING
        ) {
            state.end(order, OrderStatus.CANCELLED, nowMs, null).orders.single()
        } else {
            order
        }
    }

    /** Settles the position in [symbol] at [price] per unit at [timeMs]; null when nothing is held. */
    fun settle(
        symbol: String,
        price: BigDecimal,
        timeMs: Long,
    ): VenueSettlement? =
        if (ledger.settle(symbol, price)) {
            VenueSettlement(symbol, price, timeMs).also { state.settlements += it }
        } else {
            null
        }

    private fun match(
        order: VenueOrder,
        ticker: DeribitTicker?,
        nowMs: Long,
        arriving: Boolean,
    ): PaperChange {
        val touch = touch(order.side, ticker)
        val stopOrder = order.type == OrderType.STOP || order.type == OrderType.STOP_LIMIT
        var triggeredNow = false
        if (stopOrder && order.clientOrderId !in state.triggered) {
            if (touch == null || !crossed(order.side, touch, order.stopPrice!!)) return PaperChange()
            state.triggered += order.clientOrderId
            triggeredNow = true
        }
        val limit = order.limitPrice.takeIf { order.type == OrderType.LIMIT || order.type == OrderType.STOP_LIMIT }
        val price =
            when {
                touch == null -> null
                limit == null -> touch
                !marketable(order.side, touch, limit) -> null
                arriving || triggeredNow -> touch
                else -> limit
            }
        if (price == null) {
            if (limit == null &&
                arriving
            ) {
                return state.end(
                    order,
                    OrderStatus.REJECTED,
                    nowMs,
                    "the ${order.side.name.lowercase()} side is not quoted",
                )
            }
            val immediate = arriving || triggeredNow
            if (immediate &&
                order.timeInForce in setOf(TimeInForce.IOC, TimeInForce.FOK)
            ) {
                return state.end(order, OrderStatus.CANCELLED, nowMs, null)
            }
            return PaperChange()
        }
        if (order.reduceOnly) {
            ledger.reduceOnlyRefusal(order.symbol, order.side, order.quantity)?.let {
                return state.end(order, OrderStatus.CANCELLED, nowMs, it)
            }
        }
        return state.fill(order, price, nowMs)
    }
}
