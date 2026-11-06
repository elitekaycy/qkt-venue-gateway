package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.TickRange
import com.qkt.venuegateway.host.market.perpetual
import com.qkt.venuegateway.host.wire.WireFundingMapping
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WireInstrument
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TickRangesWireTest {
    private val base =
        """{"code":"X","kind":"perpetual","currency":"USDC","contract_size":"1","tick_size":"0.5",""" +
            """"volume_step":"0.001","volume_min":"0.001""""

    private fun json(ranges: List<TickRange>?) =
        wireJson.encodeToString(
            WireInstrument.serializer(),
            WireReads.instrument(perpetual("X").copy(tickRanges = ranges)),
        )

    @Test
    fun `an instrument without a price grid reads as before, with no tick_ranges`() {
        assertThat(json(null)).isEqualTo("$base}")
    }

    @Test
    fun `a price grid is listed as tick_ranges of decimal strings`() {
        val ranges = listOf(TickRange(BigDecimal("0"), BigDecimal("0.10"), BigDecimal("0.001")))

        assertThat(json(ranges)).isEqualTo("""$base,"tick_ranges":[{"from":"0","to":"0.10","tick":"0.001"}]}""")
    }

    @Test
    fun `native stops are reported in health as stop_orders`() {
        assertThat(WireFundingMapping.capabilities(setOf(Capability.STOP_ORDERS, Capability.BARS)))
            .containsExactly("bars", "stop_orders")
    }
}
