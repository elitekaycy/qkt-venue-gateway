package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.EventTerms
import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentFilter
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.venuegateway.adapter.VenueAdapter
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SearchChecksTest {
    private fun contract(
        code: String,
        series: String = "KXS",
    ) = Instrument(
        code,
        InstrumentKind.EVENT,
        "USD",
        BigDecimal.ONE,
        BigDecimal("0.01"),
        BigDecimal("0.01"),
        BigDecimal.ONE,
        expiryMs = 9_000,
        event = EventTerms(series, "$series-E1", StrikeType.NONE, null, null, 0, 9_000),
    )

    private class Searching(
        private val found: List<Instrument>?,
    ) : VenueAdapter by FakeVenue() {
        val asked = mutableListOf<InstrumentFilter>()

        override fun search(filter: InstrumentFilter): List<Instrument>? {
            asked += filter
            return found
        }
    }

    private val active = contract("KXS-E1-A")

    @Test
    fun `a series found well formed and holding the active contract passes, asked by its series`() {
        val venue = Searching(listOf(active, contract("KXS-E1-B")))
        assertThatCode { SearchChecks.series(venue, active) }.doesNotThrowAnyException()
        assertThat(venue.asked).containsExactly(InstrumentFilter(series = "KXS"))
    }

    @Test
    fun `an adapter that cannot search, and an instrument that is no event contract, are not asked more`() {
        assertThatCode { SearchChecks.series(Searching(null), active) }.doesNotThrowAnyException()
        val venue = Searching(emptyList())
        assertThatCode { SearchChecks.series(venue, FakeVenue().instruments().first()) }.doesNotThrowAnyException()
        assertThat(venue.asked).isEmpty()
    }

    @Test
    fun `a search that misses the active contract, or finds another series, is refused`() {
        assertThatThrownBy { SearchChecks.series(Searching(listOf(contract("KXS-E1-B"))), active) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("search of series KXS")
        assertThatThrownBy { SearchChecks.series(Searching(listOf(active, contract("OTH-E1-A", "OTH"))), active) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("series found for KXS")
        assertThatThrownBy { SearchChecks.series(Searching(emptyList()), active) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("the venue lists instruments")
    }
}
