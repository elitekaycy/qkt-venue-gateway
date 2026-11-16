package com.qkt.venuegateway.host.wire

import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueQuote
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WireReadsArmsTest {
    private val ten = BigDecimal("10.50")

    @Test
    fun `a quote carries only the fields the venue gave, each exactly as written`() {
        val bare = WireReads.quote(VenueQuote("X", timeMs = 5))
        val full =
            WireReads.quote(
                VenueQuote("X", ten, ten, ten, ten, ten, ten, ten, 5, ten),
            )

        assertThat(
            listOf(bare.bid, bare.ask, bare.bidSize, bare.askSize, bare.mark, bare.markIv, bare.underlying, bare.index),
        ).containsOnlyNulls()
        assertThat(bare.time).isEqualTo(5)
        assertThat(
            listOf(full.bid, full.ask, full.bidSize, full.askSize, full.mark, full.markIv, full.underlying, full.index),
        ).containsOnly("10.50")
    }

    @Test
    fun `a mark carries its mark and its index, either of which may be absent`() {
        assertThat(WireReads.mark(VenueMark(7, null, null))).isEqualTo(com.qkt.vgp.WireMark(7))
        assertThat(WireReads.mark(VenueMark(7, ten, null)).mark).isEqualTo("10.50")
        assertThat(WireReads.mark(VenueMark(7, null, ten)).index).isEqualTo("10.50")
    }
}
