package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TickRange
import com.qkt.venuegateway.adapter.VenueBar
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat

/** The shape every listing, kline series and position report keeps, whatever the venue. */
internal object ContractChecks {
    /** Codes are unique and every instrument carries the fields its kind needs, no more. */
    fun listing(instruments: List<Instrument>) {
        assertThat(instruments).describedAs("the venue lists instruments").isNotEmpty
        assertThat(instruments.map { it.code }).describedAs("instrument codes").doesNotHaveDuplicates()
        instruments.forEach(::instrument)
    }

    /**
     * Klines [resolutionMs] long start inside `[fromMs, toMs)`, in order, on their boundaries, and are consistent
     * (the newest may still be forming: the host serves only closed bars).
     */
    fun klines(
        bars: List<VenueBar>,
        resolutionMs: Long,
        fromMs: Long,
        toMs: Long,
    ) {
        assertThat(bars).describedAs("klines over the last window").isNotEmpty
        assertThat(bars.zipWithNext().all { (a, b) -> a.startMs < b.startMs }).describedAs("klines ascend").isTrue
        bars.forEach { bar ->
            assertThat(bar.startMs).describedAs("kline start").isGreaterThanOrEqualTo(fromMs).isLessThan(toMs)
            assertThat(bar.startMs % resolutionMs).describedAs("kline ${bar.startMs} on its boundary").isZero
            assertThat(bar.high).describedAs("high of ${bar.startMs}").isGreaterThanOrEqualTo(bar.open.max(bar.close))
            assertThat(bar.low).describedAs("low of ${bar.startMs}").isLessThanOrEqualTo(bar.open.min(bar.close))
            assertThat(bar.volume).describedAs("volume of ${bar.startMs}").isGreaterThanOrEqualTo(BigDecimal.ZERO)
        }
    }

    /** The account's signed net quantity in [symbol], across every row (tickets when hedging). */
    fun net(
        positions: Positions,
        symbol: String,
    ): BigDecimal =
        positions.rows.filter { it.symbol == symbol }.fold(BigDecimal.ZERO) { sum, row -> sum + row.quantity }

    /** [quantity] signed by [side]: positive to buy, negative to sell. */
    fun signed(
        side: Side,
        quantity: BigDecimal,
    ): BigDecimal = if (side == Side.BUY) quantity else quantity.negate()

    internal fun instrument(i: Instrument) {
        val what = "instrument ${i.code}"
        assertThat(i.code).describedAs(what).isNotBlank
        assertThat(i.currency).describedAs("$what currency").isNotBlank
        listOf(i.contractSize, i.tickSize, i.volumeStep, i.volumeMin).forEach {
            assertThat(it).describedAs("$what sizes").isPositive
        }
        val option = i.kind == InstrumentKind.OPTION
        assertThat(i.expiryMs != null)
            .describedAs("$what expiry")
            .isEqualTo(option || i.kind == InstrumentKind.FUTURE || i.kind == InstrumentKind.EVENT)
        assertThat(i.strike != null).describedAs("$what strike").isEqualTo(option)
        assertThat(i.underlying != null).describedAs("$what underlying").isEqualTo(option)
        assertThat(i.right).describedAs("$what right").isIn(if (option) listOf("call", "put") else listOf(null))
        EventChecks.terms(i)
        i.tickRanges?.let { ticks(what, i.tickSize, it) }
    }

    /** Ranges ascend without gaps, each with a positive tick, and [tickSize] is the finest of them. */
    private fun ticks(
        what: String,
        tickSize: BigDecimal,
        ranges: List<TickRange>,
    ) {
        assertThat(ranges).describedAs("$what tick ranges").isNotEmpty
        ranges.forEach {
            assertThat(it.tick).describedAs("$what tick from ${it.from}").isPositive
            assertThat(it.from).describedAs("$what tick range from ${it.from}").isLessThan(it.to)
        }
        assertThat(ranges.zipWithNext().all { (a, b) -> a.to.compareTo(b.from) == 0 })
            .describedAs("$what tick ranges ascend without gaps")
            .isTrue
        assertThat(tickSize)
            .describedAs("$what tick size, the finest tick")
            .isEqualByComparingTo(ranges.minOf { it.tick })
    }
}
