package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.adapter.VenueUnsupportedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * What the public tape promises: declared, [Capability.TRADES] answers [activeCode]'s recent prints and
 * [Capability.LIQUIDATIONS] its liquidations (none is an answer: a quiet hour liquidates nobody), in their range,
 * oldest first, at positive prices and sizes; not declared, each is refused as [VenueUnsupportedException].
 */
internal object TapeChecks {
    fun tape(
        adapter: VenueAdapter,
        activeCode: String,
        toMs: Long,
    ) {
        val fromMs = toMs - SPAN_MS
        if (Capability.TRADES in adapter.capabilities) {
            val prints = adapter.trades(activeCode, fromMs, toMs, LIMIT)
            assertThat(
                prints,
            ).describedAs("trades of $activeCode over the last hour").isNotEmpty.hasSizeLessThanOrEqualTo(LIMIT)
            assertThat(prints.map { it.id }).describedAs("trade ids").doesNotHaveDuplicates()
            check(prints, fromMs, toMs, "trade")
        } else {
            assertThatThrownBy { adapter.trades(activeCode, fromMs, toMs, LIMIT) }
                .isInstanceOf(VenueUnsupportedException::class.java)
        }
        if (Capability.LIQUIDATIONS in adapter.capabilities) {
            val prints = adapter.liquidations(activeCode, fromMs, toMs)
            assertThat(prints.map { it.id to it.side }).describedAs("liquidation ids and sides").doesNotHaveDuplicates()
            check(prints, fromMs, toMs, "liquidation")
        } else {
            assertThatThrownBy { adapter.liquidations(activeCode, fromMs, toMs) }
                .isInstanceOf(VenueUnsupportedException::class.java)
        }
    }

    private fun check(
        prints: List<VenuePrint>,
        fromMs: Long,
        toMs: Long,
        what: String,
    ) {
        assertThat(prints.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs }).describedAs("${what}s ascend").isTrue
        prints.forEach {
            assertThat(it.timeMs).describedAs("$what ${it.id} time").isBetween(fromMs, toMs - 1)
            assertThat(it.price).describedAs("$what ${it.id} price").isPositive
            assertThat(it.size).describedAs("$what ${it.id} size").isPositive
        }
    }

    private const val SPAN_MS = 3_600_000L
    private const val LIMIT = 50
}
