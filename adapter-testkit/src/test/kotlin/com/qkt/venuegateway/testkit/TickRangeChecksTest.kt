package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.TickRange
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TickRangeChecksTest {
    private fun range(
        from: String,
        to: String,
        tick: String,
    ) = TickRange(BigDecimal(from), BigDecimal(to), BigDecimal(tick))

    private val tails = listOf(range("0", "0.1", "0.001"), range("0.1", "0.9", "0.01"), range("0.9", "1", "0.001"))

    private fun spot(
        ranges: List<TickRange>?,
        tickSize: String = "0.001",
    ) = Instrument(
        "X",
        InstrumentKind.SPOT,
        "USD",
        BigDecimal.ONE,
        BigDecimal(tickSize),
        BigDecimal.ONE,
        BigDecimal.ONE,
        tickRanges = ranges,
    )

    private fun refused(
        i: Instrument,
        reason: String,
    ) {
        assertThatThrownBy { ContractChecks.instrument(i) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining(reason)
    }

    @Test
    fun `a grid of contiguous ranges whose finest tick is the tick size passes, as does no grid`() {
        assertThatCode { ContractChecks.instrument(spot(tails)) }.doesNotThrowAnyException()
        assertThatCode { ContractChecks.instrument(spot(null)) }.doesNotThrowAnyException()
    }

    @Test
    fun `an empty grid is refused`() = refused(spot(emptyList()), "tick ranges")

    @Test
    fun `a range without a positive tick is refused`() = refused(spot(listOf(range("0", "1", "0"))), "tick from 0")

    @Test
    fun `a range that does not rise is refused`() = refused(spot(listOf(range("1", "1", "0.001"))), "tick range from 1")

    @Test
    fun `ranges with a gap between them are refused`() =
        refused(spot(listOf(range("0", "0.1", "0.001"), range("0.2", "1", "0.01"))), "without gaps")

    @Test
    fun `a tick size coarser than the finest tick is refused`() = refused(spot(tails, tickSize = "0.01"), "finest tick")
}
