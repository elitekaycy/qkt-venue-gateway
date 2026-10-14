package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.adapter.VenueUnsupportedException
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/** What `DEPTH` promises: declared, a read up to now holds the present book in shape; undeclared, it is refused. */
internal object DepthChecks {
    /**
     * [code]'s order-book snapshots up to now: the present one at least, oldest first, in the window, at most
     * [VenueDepth.MAX_LEVELS] levels a side at positive prices and amounts, bids falling, asks rising, and the
     * best bid below the best ask.
     */
    fun depth(
        adapter: VenueAdapter,
        code: String,
        fromMs: Long,
        toMs: Long,
    ) {
        if (Capability.DEPTH !in adapter.capabilities) {
            assertThatThrownBy { adapter.depth(code, fromMs, toMs) }.isInstanceOf(VenueUnsupportedException::class.java)
            return
        }
        val snapshots = adapter.depth(code, fromMs, toMs)
        assertThat(snapshots).describedAs("depth of $code up to now").isNotEmpty
        assertThat(snapshots.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs }).describedAs("depth ascends").isTrue
        snapshots.forEach { book ->
            assertThat(book.timeMs).describedAs("depth time").isBetween(fromMs, toMs)
            side(book.bids, "bids at ${book.timeMs}") { a, b -> a > b }
            side(book.asks, "asks at ${book.timeMs}") { a, b -> a < b }
            val bid = book.bids.firstOrNull()?.price
            val ask = book.asks.firstOrNull()?.price
            if (bid != null && ask != null) assertThat(bid).describedAs("best bid at ${book.timeMs}").isLessThan(ask)
        }
        assertThat(snapshots.last().bids + snapshots.last().asks).describedAs("the present book of $code").isNotEmpty
    }

    private fun side(
        levels: List<VenueLevel>,
        what: String,
        inOrder: (BigDecimal, BigDecimal) -> Boolean,
    ) {
        assertThat(levels.size).describedAs("$what: levels").isLessThanOrEqualTo(VenueDepth.MAX_LEVELS)
        levels.forEach {
            assertThat(it.price).describedAs("$what: price").isPositive
            assertThat(it.amount).describedAs("$what: amount at ${it.price}").isPositive
        }
        assertThat(levels.zipWithNext().all { (a, b) -> inOrder(a.price, b.price) }).describedAs("$what: order").isTrue
    }
}
