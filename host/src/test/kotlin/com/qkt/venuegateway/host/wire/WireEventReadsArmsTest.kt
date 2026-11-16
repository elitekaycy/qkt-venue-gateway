package com.qkt.venuegateway.host.wire

import com.qkt.venuegateway.adapter.EventFee
import com.qkt.venuegateway.adapter.EventTerms
import com.qkt.venuegateway.adapter.FeeModel
import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.vgp.WireInstrument
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WireEventReadsArmsTest {
    private val contract = WireInstrument("KXINXU-26NOV06H1500-B7450", "event", "USD", "1", "0.01", "0.01", "1")
    private val terms =
        EventTerms(
            "KXINXU",
            "KXINXU-26NOV06H1500",
            StrikeType.BETWEEN,
            BigDecimal("7425"),
            BigDecimal("7475"),
            1_000,
            2_000,
        )

    @Test
    fun `a contract with no terms passes through untouched`() {
        assertThat(WireEventReads.withTerms(contract, null)).isSameAs(contract)
    }

    @Test
    fun `bounds and a fee go on the wire as given, and what is absent stays absent`() {
        val bare = WireEventReads.withTerms(contract, terms.copy(floor = null, cap = null))
        val bounded = WireEventReads.withTerms(contract, terms)
        val takerOnly =
            WireEventReads.withTerms(
                contract,
                terms.copy(fee = EventFee(FeeModel.PROBABILITY, BigDecimal("0.07"), null, BigDecimal("0.01"))),
            )
        val both =
            WireEventReads.withTerms(
                contract,
                terms.copy(
                    fee = EventFee(FeeModel.PROBABILITY, BigDecimal("0.07"), BigDecimal("0.0175"), BigDecimal("0.01")),
                ),
            )

        assertThat(listOf(bare.floor, bare.cap, bare.feeModel, bare.takerFeeRate, bare.makerFeeRate, bare.feeRounding))
            .containsOnlyNulls()
        assertThat(bounded.floor to bounded.cap).isEqualTo("7425" to "7475")
        assertThat(takerOnly.feeModel).isEqualTo("probability")
        assertThat(takerOnly.takerFeeRate).isEqualTo("0.07")
        assertThat(takerOnly.makerFeeRate).isNull()
        assertThat(takerOnly.feeRounding).isEqualTo("0.01")
        assertThat(both.makerFeeRate).isEqualTo("0.0175")
        assertThat(bounded.strikeType).isEqualTo("between")
    }
}
