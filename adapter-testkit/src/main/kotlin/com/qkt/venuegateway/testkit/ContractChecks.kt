package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueBar
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat

/** The shape every listing, bar series and position report keeps, whatever the venue. */
internal object ContractChecks {
    /** Codes are unique and every instrument carries the fields its kind needs, no more. */
    fun listing(instruments: List<Instrument>) {
        assertThat(instruments).describedAs("the venue lists instruments").isNotEmpty
        assertThat(instruments.map { it.code }).describedAs("instrument codes").doesNotHaveDuplicates()
        instruments.forEach(::instrument)
    }

    /** Bars start inside `[fromMs, toMs)`, in order, on [windowMs] boundaries, closed by [nowMs], and consistent. */
    fun bars(
        bars: List<VenueBar>,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
        nowMs: Long,
    ) {
        assertThat(bars).describedAs("bars over the last window").isNotEmpty
        assertThat(bars.zipWithNext().all { (a, b) -> a.startMs < b.startMs }).describedAs("bars ascend").isTrue
        bars.forEach { bar ->
            assertThat(bar.startMs).describedAs("bar start").isGreaterThanOrEqualTo(fromMs).isLessThan(toMs)
            assertThat(bar.startMs % windowMs).describedAs("bar ${bar.startMs} on a window boundary").isZero
            assertThat(bar.startMs + windowMs).describedAs("bar ${bar.startMs} is closed").isLessThanOrEqualTo(nowMs)
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

    private fun instrument(i: Instrument) {
        val what = "instrument ${i.code}"
        assertThat(i.code).describedAs(what).isNotBlank
        assertThat(i.currency).describedAs("$what currency").isNotBlank
        listOf(i.contractSize, i.tickSize, i.volumeStep, i.volumeMin).forEach {
            assertThat(it).describedAs("$what sizes").isPositive
        }
        val option = i.kind == InstrumentKind.OPTION
        assertThat(i.expiryMs != null)
            .describedAs("$what expiry")
            .isEqualTo(option || i.kind == InstrumentKind.FUTURE)
        assertThat(i.strike != null).describedAs("$what strike").isEqualTo(option)
        assertThat(i.underlying != null).describedAs("$what underlying").isEqualTo(option)
        assertThat(i.right).describedAs("$what right").isIn(if (option) listOf("call", "put") else listOf(null))
    }
}
