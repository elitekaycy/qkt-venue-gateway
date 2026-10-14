package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueSettlement

/** Writes each push the paper venue makes into [heard] as one line, quotes and link changes aside. */
internal class HeardListener(
    private val heard: MutableList<String>,
) : AdapterListener {
    override fun order(order: VenueOrder) {
        heard += "order ${order.clientOrderId} ${order.status}"
    }

    override fun fill(fill: VenueFill) {
        heard += "fill ${fill.clientOrderId} ${fill.price.toPlainString()}"
    }

    override fun settlement(settlement: VenueSettlement) {
        heard += "settle ${settlement.symbol} ${settlement.price.toPlainString()}"
    }

    override fun funding(funding: VenueFunding) {
        heard += "funding ${funding.symbol} ${funding.amount.toPlainString()}"
    }

    override fun quote(quote: VenueQuote) {}

    override fun quoteFeed(
        up: Boolean,
        reason: String,
    ) {}

    override fun connection(
        up: Boolean,
        reason: String,
    ) {}
}
