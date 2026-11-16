package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueSettlement
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps every push the adapter makes, in arrival order, for a test to read back. */
internal class HeardListener : AdapterListener {
    val orders = CopyOnWriteArrayList<VenueOrder>()
    val fills = CopyOnWriteArrayList<VenueFill>()
    val quotes = CopyOnWriteArrayList<VenueQuote>()
    val feed = CopyOnWriteArrayList<Pair<Boolean, String>>()
    val links = CopyOnWriteArrayList<Pair<Boolean, String>>()

    override fun order(order: VenueOrder) {
        orders += order
    }

    override fun fill(fill: VenueFill) {
        fills += fill
    }

    override fun settlement(settlement: VenueSettlement) {
        error("alpaca serves no settlements")
    }

    override fun funding(funding: VenueFunding) {
        error("alpaca charges no funding")
    }

    override fun quote(quote: VenueQuote) {
        quotes += quote
    }

    override fun quoteFeed(
        up: Boolean,
        reason: String,
    ) {
        feed += up to reason
    }

    override fun connection(
        up: Boolean,
        reason: String,
    ) {
        links += up to reason
    }
}
