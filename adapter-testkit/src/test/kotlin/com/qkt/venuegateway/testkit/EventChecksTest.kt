package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.EventFee
import com.qkt.venuegateway.adapter.EventTerms
import com.qkt.venuegateway.adapter.FeeModel
import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EventChecksTest {
    private val ladder =
        EventTerms(
            series = "KXINXU",
            event = "KXINXU-26NOV06H1500",
            strikeType = StrikeType.GREATER_OR_EQUAL,
            floor = BigDecimal("7425"),
            cap = null,
            openAtMs = 1_000,
            closeAtMs = 2_000,
        )

    private fun contract(
        terms: EventTerms?,
        kind: InstrumentKind = InstrumentKind.EVENT,
        expiryMs: Long? = 3_000,
    ) = Instrument(
        code = "KXINXU-26NOV06H1500-T7425",
        kind = kind,
        currency = "USD",
        contractSize = BigDecimal.ONE,
        tickSize = BigDecimal("0.01"),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal.ONE,
        expiryMs = expiryMs,
        event = terms,
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
    fun `a well-formed ladder contract passes`() {
        assertThatCode { ContractChecks.instrument(contract(ladder)) }.doesNotThrowAnyException()
    }

    @Test
    fun `an event without terms is refused`() = refused(contract(null), "event terms")

    @Test
    fun `terms on an instrument that is not an event are refused`() =
        refused(contract(ladder, InstrumentKind.SPOT, expiryMs = null), "event terms")

    @Test
    fun `an event without an expiry is refused`() = refused(contract(ladder, expiryMs = null), "expiry")

    @Test
    fun `a between contract without a cap is refused`() =
        refused(contract(ladder.copy(strikeType = StrikeType.BETWEEN)), "(floor, cap)")

    @Test
    fun `a between contract whose floor is above its cap is refused`() =
        refused(contract(ladder.copy(strikeType = StrikeType.BETWEEN, cap = BigDecimal("7400"))), "floor not above cap")

    @Test
    fun `an exact-count bucket with equal floor and cap passes`() {
        val exact = ladder.copy(strikeType = StrikeType.BETWEEN, floor = BigDecimal("45"), cap = BigDecimal("45"))
        assertThatCode { ContractChecks.instrument(contract(exact)) }.doesNotThrowAnyException()
    }

    @Test
    fun `a plain question with a floor is refused`() =
        refused(contract(ladder.copy(strikeType = StrikeType.NONE)), "(floor, cap)")

    @Test
    fun `a less-than contract carries a cap and no floor`() {
        val less = ladder.copy(strikeType = StrikeType.LESS, floor = null, cap = BigDecimal("7350"))
        assertThatCode { ContractChecks.instrument(contract(less)) }.doesNotThrowAnyException()
    }

    @Test
    fun `an event that closes before it opens is refused`() = refused(contract(ladder.copy(closeAtMs = 500)), "close")

    @Test
    fun `an outcome outside zero to one is refused`() =
        refused(contract(ladder.copy(outcome = BigDecimal("1.5"))), "outcome")

    private fun fee(
        taker: String,
        rounding: String,
    ) = EventFee(FeeModel.PROBABILITY, BigDecimal(taker), null, BigDecimal(rounding))

    @Test
    fun `a fee rate of one or more is refused`() = refused(contract(ladder.copy(fee = fee("1", "0.01"))), "fee rate")

    @Test
    fun `a fee without a positive rounding unit is refused`() =
        refused(contract(ladder.copy(fee = fee("0.07", "0"))), "rounding")

    @Test
    fun `a blank series is refused`() = refused(contract(ladder.copy(series = " ")), "series")
}
