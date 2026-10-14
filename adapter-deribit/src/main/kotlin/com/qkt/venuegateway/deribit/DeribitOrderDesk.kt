package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.DeribitErrors.venue
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitTrading

/**
 * One Deribit account's orders, every lookup by label: placing (only a listed instrument of [currency] in
 * [listing]: any other would be traded outside every read), cancelling, editing and finding the newest order
 * of a label (a fired stop is a newer order than the stop). Stops trigger on [stopTrigger]. A market order
 * Deribit left working with a remainder is cancelled before it is reported ([DeribitMarketRemainders]).
 */
internal class DeribitOrderDesk(
    private val account: DeribitTrading,
    private val currency: String,
    private val listing: DeribitListing,
    private val stopTrigger: String,
) {
    private val remainders = DeribitMarketRemainders(account, currency)

    /** Whether the pushed [order] is reported as it is ([DeribitMarketRemainders.reportable]). */
    fun reportable(order: DeribitOrder) = remainders.reportable(order)

    /** The working orders, any market remainder among them cancelled first and so no longer listed. */
    fun open() =
        venue { account.openOrders(currency).map(remainders::settled) }
            .mapNotNull(DeribitMapping::order)
            .filter { it.status == OrderStatus.WORKING }

    fun place(order: NewOrder): VenueOrder {
        if (venue { listing.find(order.symbol) } == null) {
            throw VenueRefusedException("${order.symbol} is not a listed $currency contract of this account")
        }
        val placed = venue { remainders.settled(account.place(DeribitMapping.newOrder(order, stopTrigger))) }
        return DeribitMapping.order(placed) ?: error("deribit answered order ${order.clientOrderId} without its label")
    }

    /** Cancels the order labelled [clientOrderId] and returns it as it now stands (filled, if it filled first). */
    fun cancel(clientOrderId: String): VenueOrder? {
        venue { account.cancelByLabel(currency, clientOrderId) }
        return byLabel(clientOrderId)
    }

    fun modify(
        clientOrderId: String,
        change: OrderChange,
    ): VenueOrder? {
        val current = byLabel(clientOrderId) ?: return null
        val quantity = change.quantity ?: current.quantity
        val edited =
            venue { account.editByLabel(clientOrderId, current.symbol, quantity, change.limitPrice, change.stopPrice) }
        return DeribitMapping.order(edited)
    }

    fun byLabel(clientOrderId: String): VenueOrder? =
        venue {
            account.ordersByLabel(
                currency,
                clientOrderId,
            )
        }.maxByOrNull { it.updatedMs }?.let { venue { remainders.settled(it) } }?.let(DeribitMapping::order)
}
