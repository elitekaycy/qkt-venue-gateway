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

class EventStrikeTypesTest {
    private val floor = BigDecimal("7425")
    private val cap = BigDecimal("7475")

    private fun contract(
        type: StrikeType,
        floor: BigDecimal?,
        cap: BigDecimal?,
        fee: EventFee? = null,
    ) = Instrument(
        code = "KXINXU-26NOV06H1500-B7450",
        kind = InstrumentKind.EVENT,
        currency = "USD",
        contractSize = BigDecimal.ONE,
        tickSize = BigDecimal("0.01"),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal.ONE,
        expiryMs = 3_000,
        event = EventTerms("KXINXU", "KXINXU-26NOV06H1500", type, floor, cap, 1_000, 2_000, fee = fee),
    )

    @Test
    fun `every strike type passes with exactly the bounds it names`() {
        val shapes =
            mapOf(
                StrikeType.BETWEEN to (floor to cap),
                StrikeType.GREATER to (floor to null),
                StrikeType.GREATER_OR_EQUAL to (floor to null),
                StrikeType.LESS to (null to cap),
                StrikeType.LESS_OR_EQUAL to (null to cap),
                StrikeType.CUSTOM to (null to null),
                StrikeType.NONE to (null to null),
            )

        shapes.forEach { (type, bounds) ->
            assertThatCode { ContractChecks.instrument(contract(type, bounds.first, bounds.second)) }
                .describedAs(type.name)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `a strike type given the bounds of another is refused`() {
        listOf(
            contract(StrikeType.GREATER, null, cap),
            contract(StrikeType.LESS_OR_EQUAL, floor, null),
            contract(StrikeType.CUSTOM, floor, cap),
        ).forEach {
            assertThatThrownBy { ContractChecks.instrument(it) }.hasMessageContaining("(floor, cap)")
        }
    }

    @Test
    fun `a maker fee rate is checked as the taker rate is`() {
        val maker = EventFee(FeeModel.PROBABILITY, BigDecimal("0.07"), BigDecimal("1.2"), BigDecimal("0.01"))
        val fine = EventFee(FeeModel.PROBABILITY, BigDecimal("0.07"), BigDecimal("0.0175"), BigDecimal("0.01"))

        assertThatThrownBy { ContractChecks.instrument(contract(StrikeType.NONE, null, null, maker)) }
            .hasMessageContaining("fee rate")
        assertThatCode {
            ContractChecks.instrument(
                contract(StrikeType.NONE, null, null, fine),
            )
        }.doesNotThrowAnyException()
    }
}
