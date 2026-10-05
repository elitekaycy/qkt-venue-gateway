package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueUnsupportedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * What each optional capability promises: declared, it answers in shape; not declared, settlements, funding,
 * funding rates and mark prices are refused as [VenueUnsupportedException] (bars and quotes are only checked when
 * declared: a venue without them has nothing to refuse).
 */
internal object CapabilityChecks {
    /** When declared, [activeCode]'s recent bars are well formed and a subscription quotes it, bid at or below ask. */
    fun barsAndQuotes(
        adapter: VenueAdapter,
        listener: RecordingListener,
        activeCode: String,
        windowMs: Long,
        pushTimeoutMs: Long,
    ) {
        val nowMs = System.currentTimeMillis()
        val fromMs = nowMs - BARS_CHECKED * windowMs
        if (Capability.BARS in adapter.capabilities) {
            ContractChecks.bars(adapter.bars(activeCode, windowMs, fromMs, nowMs), windowMs, fromMs, nowMs, nowMs)
        }
        if (Capability.QUOTES !in adapter.capabilities) return
        adapter.subscribeQuotes(setOf(activeCode), emptySet())
        listener.await("a quote of $activeCode", pushTimeoutMs) { listener.quotes.any { it.symbol == activeCode } }
        val quote = listener.quotes.first { it.symbol == activeCode }
        assertThat(quote.timeMs).isPositive
        if (quote.bid != null && quote.ask != null) assertThat(quote.bid).isLessThanOrEqualTo(quote.ask)
    }

    /** Funding records are unique, in their window, oldest first, on a named symbol, and never zero. */
    fun funding(
        adapter: VenueAdapter,
        fromMs: Long,
        toMs: Long,
    ) {
        if (Capability.FUNDING !in adapter.capabilities) {
            assertThatThrownBy { adapter.funding(fromMs, toMs) }.isInstanceOf(VenueUnsupportedException::class.java)
            return
        }
        val records = adapter.funding(fromMs, toMs)
        assertThat(records.map { it.fundingId }).describedAs("funding ids").doesNotHaveDuplicates()
        assertThat(records.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs }).describedAs("funding ascends").isTrue
        records.forEach {
            assertThat(it.timeMs).describedAs("funding ${it.fundingId} time").isBetween(fromMs, toMs)
            assertThat(it.symbol).describedAs("funding ${it.fundingId} symbol").isNotBlank
            assertThat(it.currency).describedAs("funding ${it.fundingId} currency").isNotBlank
            assertThat(it.amount.signum()).describedAs("funding ${it.fundingId} amount").isNotZero
        }
    }

    /** [perpetual]'s published rates over the window: some, oldest first, inside it, at positive prices. */
    fun fundingRates(
        adapter: VenueAdapter,
        perpetual: String?,
        fromMs: Long,
        toMs: Long,
    ) {
        if (Capability.FUNDING_RATES !in adapter.capabilities) {
            assertThatThrownBy { adapter.fundingRates(perpetual ?: "any", fromMs, toMs) }
                .isInstanceOf(VenueUnsupportedException::class.java)
            return
        }
        assertThat(perpetual).describedAs("perpetualCode, which an adapter serving funding rates names").isNotNull
        val rates = adapter.fundingRates(perpetual!!, fromMs, toMs)
        assertThat(rates).describedAs("funding rates of $perpetual over the window").isNotEmpty
        assertThat(rates.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs }).describedAs("rates ascend").isTrue
        rates.forEach {
            assertThat(it.timeMs).describedAs("rate time").isBetween(fromMs, toMs)
            it.price?.let { price -> assertThat(price).describedAs("rate price at ${it.timeMs}").isPositive }
        }
    }

    /** [activeCode]'s marks over its recent windows: some, at most one a window, inside the range, positive. */
    fun marks(
        adapter: VenueAdapter,
        activeCode: String,
        windowMs: Long,
    ) {
        val toMs = System.currentTimeMillis() / windowMs * windowMs
        val fromMs = toMs - BARS_CHECKED * windowMs
        if (Capability.MARK_PRICES !in adapter.capabilities) {
            assertThatThrownBy { adapter.marks(activeCode, windowMs, fromMs, toMs) }
                .isInstanceOf(VenueUnsupportedException::class.java)
            return
        }
        val marks = adapter.marks(activeCode, windowMs, fromMs, toMs)
        assertThat(marks).describedAs("marks of $activeCode over its last $BARS_CHECKED windows").isNotEmpty
        assertThat(marks.map { it.timeMs / windowMs }).describedAs("one mark per window").doesNotHaveDuplicates()
        assertThat(marks.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs }).describedAs("marks ascend").isTrue
        marks.forEach {
            assertThat(it.timeMs).describedAs("mark time").isBetween(fromMs, toMs - 1)
            assertThat(it.mark ?: it.index).describedAs("mark or index at ${it.timeMs}").isNotNull
            it.mark?.let { mark -> assertThat(mark).describedAs("mark at ${it.timeMs}").isPositive }
            it.index?.let { index -> assertThat(index).describedAs("index at ${it.timeMs}").isPositive }
        }
    }

    /** Settlements lie in their window, oldest first; an adapter that does not declare them refuses. */
    fun settlements(
        adapter: VenueAdapter,
        fromMs: Long,
        toMs: Long,
    ) {
        if (Capability.SETTLEMENTS !in adapter.capabilities) {
            assertThatThrownBy { adapter.settlements(fromMs, toMs) }.isInstanceOf(VenueUnsupportedException::class.java)
            return
        }
        val settled = adapter.settlements(fromMs, toMs)
        assertThat(
            settled.zipWithNext().all { (a, b) ->
                a.timeMs <= b.timeMs
            },
        ).describedAs("settlements ascend").isTrue
        settled.forEach { assertThat(it.timeMs).describedAs("settlement of ${it.symbol}").isBetween(fromMs, toMs) }
    }

    private const val BARS_CHECKED = 30L
}
