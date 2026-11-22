package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat

/** The shape a listed Kalshi market keeps as an `event` instrument: the contract kit event rules and its own fields. */
internal object KalshiInstrumentRules {
    fun wellFormed(
        i: Instrument,
        ticker: String,
        category: String?,
    ) {
        val what = "instrument $ticker"
        assertThat(i.code).describedAs("$what code").isEqualTo(ticker)
        assertThat(i.kind).describedAs("$what kind").isEqualTo(InstrumentKind.EVENT)
        assertThat(i.category).describedAs("$what category").isEqualTo(category)
        assertThat(i.tickSize).describedAs("$what tick").isPositive
        i.tickRanges?.forEach { assertThat(it.tick).describedAs("$what tick range tick").isPositive }
        val e = i.event!!
        assertThat(e.series).describedAs("$what series").isNotBlank
        assertThat(e.event).describedAs("$what event").isNotBlank
        assertThat(e.closeAtMs).describedAs("$what close").isGreaterThanOrEqualTo(e.openAtMs)
        val bounds = (e.floor != null) to (e.cap != null)
        val expected =
            when (e.strikeType) {
                StrikeType.BETWEEN -> true to true
                StrikeType.GREATER, StrikeType.GREATER_OR_EQUAL -> true to false
                StrikeType.LESS, StrikeType.LESS_OR_EQUAL -> false to true
                StrikeType.CUSTOM, StrikeType.NONE -> false to false
            }
        assertThat(bounds).describedAs("$what (floor, cap) present for ${e.strikeType}").isEqualTo(expected)
        e.outcome?.let { assertThat(it).describedAs("$what outcome").isBetween(BigDecimal.ZERO, BigDecimal.ONE) }
        e.fee?.let { fee ->
            listOfNotNull(fee.takerRate, fee.makerRate).forEach {
                assertThat(it)
                    .describedAs("$what fee rate")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                    .isLessThan(BigDecimal.ONE)
            }
        }
    }
}
