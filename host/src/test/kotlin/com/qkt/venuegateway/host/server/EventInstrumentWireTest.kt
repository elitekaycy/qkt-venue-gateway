package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.EventFee
import com.qkt.venuegateway.adapter.EventTerms
import com.qkt.venuegateway.adapter.FeeModel
import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WireInstrument
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An event contract reaches qkt with what it asks and when; every other instrument reads as it did before. */
class EventInstrumentWireTest {
    private fun json(i: Instrument) = wireJson.encodeToString(WireInstrument.serializer(), WireReads.instrument(i))

    private fun contract(
        code: String,
        terms: EventTerms,
    ) = Instrument(
        code = code,
        kind = InstrumentKind.EVENT,
        currency = "USD",
        contractSize = BigDecimal.ONE,
        tickSize = BigDecimal("0.01"),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal.ONE,
        expiryMs = 1_794_006_000_000,
        event = terms,
    )

    private val ladder =
        EventTerms(
            series = "KXINXU",
            event = "KXINXU-26NOV06H1500",
            strikeType = StrikeType.GREATER_OR_EQUAL,
            floor = BigDecimal("7425"),
            cap = null,
            openAtMs = 1_794_002_400_000,
            closeAtMs = 1_794_006_000_000,
            occasion = "26NOV06H1500",
            frequency = "1h",
            title = "7,425 or above",
            fee = EventFee(FeeModel.PROBABILITY, BigDecimal("0.07"), BigDecimal("0.0175"), BigDecimal("0.01")),
        )

    @Test
    fun `a ladder contract lists its series, event, strike, times and fee shape`() {
        assertThat(json(contract("KXINXU-26NOV06H1500-T7425", ladder))).isEqualTo(
            """{"code":"KXINXU-26NOV06H1500-T7425","kind":"event","currency":"USD","contract_size":"1",""" +
                """"tick_size":"0.01","volume_step":"0.01","volume_min":"1","expiry":1794006000000,""" +
                """"series":"KXINXU","event":"KXINXU-26NOV06H1500",""" +
                """"strike_type":"greater_or_equal","floor":"7425",""" +
                """"open_at":1794002400000,"close_at":1794006000000,"occasion":"26NOV06H1500",""" +
                """"can_close_early":false,"exclusive":false,"frequency":"1h","title":"7,425 or above",""" +
                """"fee_model":"probability","taker_fee_rate":"0.07","maker_fee_rate":"0.0175",""" +
                """"fee_rounding":"0.01"}""",
        )
    }

    @Test
    fun `a resolved-no contract writes its outcome as zero`() {
        val settled = contract("KXINXU-26NOV06H1500-T7425", ladder.copy(outcome = BigDecimal.ZERO))
        assertThat(json(settled)).contains(""""outcome":"0"""")
    }

    @Test
    fun `a head-to-head side with no strike lists custom with no bounds and its game time`() {
        val side =
            EventTerms(
                series = "KXNBAGAME",
                event = "KXNBAGAME-26NOV09WASDET",
                strikeType = StrikeType.CUSTOM,
                floor = null,
                cap = null,
                openAtMs = 1_794_000_000_000,
                closeAtMs = 1_794_500_000_000,
                occursAtMs = 1_794_300_000_000,
                occasion = "26NOV09WASDET",
                canCloseEarly = true,
                exclusive = true,
                title = "Detroit",
            )
        val text = json(contract("KXNBAGAME-26NOV09WASDET-DET", side))
        assertThat(text)
            .contains(""""strike_type":"custom"""", """"occurs_at":1794300000000""", """"can_close_early":true""")
            .contains(""""exclusive":true""", """"title":"Detroit"""")
            .doesNotContain(""""floor"""", """"cap"""", """"fee_model"""")
    }

    @Test
    fun `an instrument that is not an event carries none of the event fields`() {
        val future =
            Instrument(
                "BTC-27DEC26",
                InstrumentKind.FUTURE,
                "USD",
                BigDecimal.TEN,
                BigDecimal("0.5"),
                BigDecimal.TEN,
                BigDecimal.TEN,
                expiryMs = 1_798_329_600_000,
            )
        assertThat(json(future)).isEqualTo(
            """{"code":"BTC-27DEC26","kind":"future","currency":"USD","contract_size":"10","tick_size":"0.5",""" +
                """"volume_step":"10","volume_min":"10","expiry":1798329600000}""",
        )
    }
}
