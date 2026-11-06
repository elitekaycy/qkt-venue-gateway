package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat

/** The shape an event contract's terms keep: present exactly on events, bounds that match the strike type. */
internal object EventChecks {
    fun terms(i: Instrument) {
        val what = "instrument ${i.code}"
        val e = i.event
        assertThat(e != null).describedAs("$what event terms").isEqualTo(i.kind == InstrumentKind.EVENT)
        if (e == null) return
        assertThat(e.series).describedAs("$what series").isNotBlank
        assertThat(e.event).describedAs("$what event").isNotBlank
        assertThat(e.closeAtMs).describedAs("$what close").isGreaterThanOrEqualTo(e.openAtMs)
        val bounds = e.floor != null to (e.cap != null)
        val expected =
            when (e.strikeType) {
                StrikeType.BETWEEN -> true to true
                StrikeType.GREATER, StrikeType.GREATER_OR_EQUAL -> true to false
                StrikeType.LESS, StrikeType.LESS_OR_EQUAL -> false to true
                StrikeType.CUSTOM, StrikeType.NONE -> false to false
            }
        assertThat(bounds).describedAs("$what (floor, cap) present for ${e.strikeType}").isEqualTo(expected)
        if (e.floor != null && e.cap != null) {
            assertThat(e.floor).describedAs("$what floor below cap").isLessThan(e.cap)
        }
        e.outcome?.let { assertThat(it).describedAs("$what outcome").isBetween(BigDecimal.ZERO, BigDecimal.ONE) }
        e.fee?.let { fee ->
            listOfNotNull(fee.takerRate, fee.makerRate).forEach {
                assertThat(it)
                    .describedAs("$what fee rate")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                    .isLessThan(BigDecimal.ONE)
            }
            assertThat(fee.rounding).describedAs("$what fee rounding").isPositive
        }
    }
}
