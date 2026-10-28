package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.Tradability
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WireInstrument
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An equity reaches qkt with its kind, its venue category and what the account may do with it; nothing else moves. */
class EquityInstrumentWireTest {
    private fun json(i: Instrument) = wireJson.encodeToString(WireInstrument.serializer(), WireReads.instrument(i))

    private fun share(trading: Tradability) =
        Instrument(
            code = "AAPL",
            kind = InstrumentKind.EQUITY,
            currency = "USD",
            contractSize = BigDecimal.ONE,
            tickSize = BigDecimal("0.01"),
            volumeStep = BigDecimal("0.000000001"),
            volumeMin = BigDecimal("0.000000001"),
            category = "us_equity",
            trading = trading,
        )

    @Test
    fun `a share is listed as an equity with its category, and a restricted one names its trading mode`() {
        assertThat(json(share(Tradability.FULL))).isEqualTo(
            """{"code":"AAPL","kind":"equity","currency":"USD","contract_size":"1","tick_size":"0.01",""" +
                """"volume_step":"0.000000001","volume_min":"0.000000001","category":"us_equity"}""",
        )
        assertThat(json(share(Tradability.LONG_ONLY))).endsWith(""""category":"us_equity","trading":"long_only"}""")
        assertThat(json(share(Tradability.CLOSE_ONLY))).contains(""""trading":"close_only"""")
    }

    @Test
    fun `an instrument with no category and full trading reads exactly as it did before either existed`() {
        val perp =
            Instrument(
                "BTC-PERPETUAL",
                InstrumentKind.PERPETUAL,
                "USD",
                BigDecimal.TEN,
                BigDecimal("0.5"),
                BigDecimal.TEN,
                BigDecimal.TEN,
            )
        assertThat(json(perp)).isEqualTo(
            """{"code":"BTC-PERPETUAL","kind":"perpetual","currency":"USD","contract_size":"10","tick_size":"0.5",""" +
                """"volume_step":"10","volume_min":"10"}""",
        )
    }
}
