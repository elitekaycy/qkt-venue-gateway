package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.bybit.BybitErrors.venue
import com.qkt.venuegateway.bybit.client.BybitPrivateClient

/**
 * One Bybit account's orders, every one by its `orderLinkId`: placing (only an instrument of the account's
 * [listing], with the position index its mode needs, [modes]), cancelling, amending, and finding an order as
 * Bybit has it now. Bybit answers a placement with its ids only, so the order is read back: a read that does
 * not find it yet (Bybit lists an order within moments) is retried [readBackTries] times, then reported
 * unavailable so the host resolves it by label. A market order needs no remainder handling: Bybit fills it
 * immediate-or-cancel within its price protection and never rests what is left (measured on testnet).
 */
internal class BybitOrderDesk(
    private val account: BybitPrivateClient,
    private val settings: BybitSettings,
    private val listing: BybitListing,
    private val modes: BybitPositionModes,
    private val readBackTries: Int = 10,
    private val pauseMs: Long = 200,
) {
    /** The working orders the gateway sent. */
    fun open(): List<VenueOrder> =
        venue { account.openOrders() }.mapNotNull(BybitMapping::order).filter { it.status == OrderStatus.WORKING }

    fun place(order: NewOrder): VenueOrder {
        if (venue { listing.find(order.symbol) } == null) {
            throw VenueRefusedException(
                "${order.symbol} is not a listed ${settings.currency} ${settings.category} instrument of this account",
            )
        }
        val sent = BybitOrderMapping.newOrder(order, settings, modes.index(order))
        try {
            venue { account.place(sent) }
        } catch (e: VenueRefusedException) {
            if (e.reason.contains("position idx not match position mode")) modes.forget(order.symbol)
            throw e
        }
        repeat(readBackTries) {
            byLabel(order.clientOrderId)?.let { return it }
            Thread.sleep(pauseMs)
        }
        throw VenueUnavailableException("bybit took order ${order.clientOrderId} but does not list it yet")
    }

    /** Cancels the order labelled [clientOrderId] and returns it as it now stands (filled, if it filled first). */
    fun cancel(clientOrderId: String): VenueOrder? {
        val current = byLabel(clientOrderId) ?: return null
        if (current.status != OrderStatus.WORKING) return current
        try {
            venue { account.cancel(current.symbol, clientOrderId) }
        } catch (e: VenueRefusedException) {
            // The order ended between the read and the cancel (Bybit: order not exists or too late to cancel).
            if (!e.reason.startsWith("bybit $ORDER_GONE:")) throw e
        }
        return byLabel(clientOrderId)
    }

    fun modify(
        clientOrderId: String,
        change: OrderChange,
    ): VenueOrder? {
        val current = byLabel(clientOrderId) ?: return null
        venue { account.amend(current.symbol, clientOrderId, change.quantity, change.limitPrice, change.stopPrice) }
        return byLabel(clientOrderId)
    }

    fun byLabel(clientOrderId: String): VenueOrder? =
        venue { account.orderByLink(clientOrderId) }?.let(BybitMapping::order)

    private companion object {
        const val ORDER_GONE = 110001
    }
}
