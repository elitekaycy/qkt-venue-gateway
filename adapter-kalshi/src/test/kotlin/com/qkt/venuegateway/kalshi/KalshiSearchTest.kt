package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.InstrumentFilter
import com.qkt.venuegateway.adapter.Tradability
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.HOURLY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A series or an event read from Kalshi itself, whatever the `series` setting lists; answers recorded from Kalshi. */
class KalshiSearchTest {
    private val venue = KalshiVenue()

    @AfterEach
    fun stop() = venue.close()

    private fun eventReads() = venue.server.requests.filter { it.url.encodedPath.endsWith("/events") }

    @Test
    fun `a series outside the scope is found by its ticker, in code order, and is not traded`() {
        venue.adapter(series = HOURLY).use { adapter ->
            val found = adapter.search(InstrumentFilter(series = "KXGDPYEAR"))!!

            assertThat(found.map { it.code }).contains("KXGDPYEAR-27-T0.1", "KXGDPYEAR-27-B0.3").isSorted
            assertThat(found.map { it.event!!.series }.toSet()).containsExactly("KXGDPYEAR")
            assertThat(found.map { it.trading }.toSet()).containsExactly(Tradability.DISABLED)
            assertThat(eventReads().map { it.param("series_ticker") }).containsExactly("KXGDPYEAR")
            assertThat(adapter.instruments().map { it.event!!.series }.toSet()).containsExactly(HOURLY)
        }
    }

    @Test
    fun `a series inside the scope, or any series under a star, is found as it is traded`() {
        venue.adapter(series = HOURLY).use { adapter ->
            val found = adapter.search(InstrumentFilter(series = HOURLY))!!
            assertThat(found.map { it.code }).containsExactly("$HOURLY-26NOV16H03-UP")
            assertThat(found.single().trading).isEqualTo(Tradability.FULL)
        }
        venue.adapter(series = "*").use { adapter ->
            val found = adapter.search(InstrumentFilter(series = "KXGDPYEAR"))!!
            assertThat(found.map { it.trading }.toSet()).doesNotContain(Tradability.DISABLED)
        }
    }

    @Test
    fun `an event is found with its markets, settled or not, and an event Kalshi lacks finds nothing`() {
        venue.adapter(series = HOURLY).use { adapter ->
            val found = adapter.search(InstrumentFilter(series = "IGNORED", event = "KXHIGHNY-26NOV04"))!!

            assertThat(found.map { it.code }).contains(KalshiVenue.SETTLED, "KXHIGHNY-26NOV04-T70")
            assertThat(found.map { it.event!!.event }.toSet()).containsExactly("KXHIGHNY-26NOV04")
            assertThat(found.map { it.trading }.toSet()).containsExactly(Tradability.DISABLED)
            assertThat(eventReads()).isEmpty()
            assertThat(adapter.search(InstrumentFilter(event = "NOPE-27"))).isEmpty()
        }
    }
}
