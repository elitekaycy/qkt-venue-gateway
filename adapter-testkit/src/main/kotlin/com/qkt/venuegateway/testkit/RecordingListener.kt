package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueSettlement
import java.util.concurrent.CopyOnWriteArrayList

/** Records everything an adapter pushes, in arrival order, for contract assertions. */
class RecordingListener : AdapterListener {
    val orders = CopyOnWriteArrayList<VenueOrder>()
    val fills = CopyOnWriteArrayList<VenueFill>()
    val settlements = CopyOnWriteArrayList<VenueSettlement>()
    val funding = CopyOnWriteArrayList<VenueFunding>()
    val quotes = CopyOnWriteArrayList<VenueQuote>()
    val connections = CopyOnWriteArrayList<Boolean>()

    override fun order(order: VenueOrder) {
        orders += order
    }

    override fun fill(fill: VenueFill) {
        fills += fill
    }

    override fun settlement(settlement: VenueSettlement) {
        settlements += settlement
    }

    override fun funding(funding: VenueFunding) {
        this.funding += funding
    }

    override fun quote(quote: VenueQuote) {
        quotes += quote
    }

    override fun connection(
        up: Boolean,
        reason: String,
    ) {
        connections += up
    }

    /** Waits up to [timeoutMs] for [condition]; fails naming [what] otherwise. */
    fun await(
        what: String,
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val POLL_MS = 20L
    }
}
