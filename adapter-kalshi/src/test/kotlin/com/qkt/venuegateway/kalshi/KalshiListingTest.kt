package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.InstrumentFilter
import com.qkt.venuegateway.adapter.Tradability
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.CURRY
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.HOURLY
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** What the `series` setting makes the listing read, each answer recorded from the demo. */
class KalshiListingTest {
    private val venue = KalshiVenue()
    private val now = AtomicLong(1_794_800_000_000L)

    @AfterEach
    fun stop() = venue.close()

    private fun paths() = venue.server.requests.map { it.url.encodedPath.removePrefix("/trade-api/v2") }

    private fun eventReads() = venue.server.requests.filter { it.url.encodedPath.endsWith("/events") }

    @Test
    fun `only the named series are read, each by its ticker, and never the whole catalogue`() {
        venue.adapter(series = "KXNBA3D, $HOURLY", clock = now::get).use { adapter ->
            val listed = adapter.instruments()

            assertThat(listed).hasSize(8)
            assertThat(listed.map { it.event!!.series }.toSet()).containsExactlyInAnyOrder("KXNBA3D", HOURLY)
            assertThat(listed.single { it.event!!.series == HOURLY }.category).isEqualTo("Test")
            assertThat(eventReads().map { it.param("series_ticker") }).containsExactly("KXNBA3D", HOURLY)
            assertThat(eventReads().map { it.param("status") }.toSet()).containsExactly("open")
            assertThat(paths()).contains("/series/KXNBA3D", "/series/$HOURLY").doesNotContain("/series")
            assertThat(adapter.listingRefreshMs).isEqualTo(60_000L)
        }
    }

    @Test
    fun `a listing of named series is read again after a minute, and on a miss at most every ten seconds`() {
        venue.adapter(clock = now::get).use { adapter ->
            adapter.instruments()
            assertThatThrownBy { adapter.trades("NOPE", 0L, 1L, 1) }.isInstanceOf(VenueRefusedException::class.java)
            now.addAndGet(59_000L)
            adapter.instruments()
            assertThat(eventReads()).hasSize(1)

            assertThatThrownBy { adapter.trades("NOPE", 0L, 1L, 1) }.isInstanceOf(VenueRefusedException::class.java)
            assertThat(eventReads()).hasSize(2)
            now.addAndGet(60_000L)
            adapter.instruments()
            assertThat(eventReads()).hasSize(3)
        }
    }

    @Test
    fun `a star lists the whole catalogue, kept an hour and not read again on a miss`() {
        venue.adapter(series = "*", clock = now::get).use { adapter ->
            assertThat(adapter.instruments()).hasSize(7)
            assertThat(eventReads().single().param("series_ticker")).isNull()
            assertThat(paths()).contains("/series")
            assertThat(adapter.listingRefreshMs).isEqualTo(3_600_000L)

            now.addAndGet(3_599_000L)
            assertThatThrownBy { adapter.trades("NOPE", 0L, 1L, 1) }.isInstanceOf(VenueRefusedException::class.java)
            assertThat(eventReads()).hasSize(1)
            now.addAndGet(1_000L)
            adapter.instruments()
            assertThat(eventReads()).hasSize(2)
        }
    }

    @Test
    fun `a named series Kalshi does not have fails the listing by its name`() {
        venue.adapter(series = "KXNBA3D,NOPE").use { adapter ->
            assertThatThrownBy { adapter.instruments() }
                .hasMessageContaining("setting series names NOPE, a series kalshi does not have")
            assertThat(eventReads()).isEmpty()
        }
    }

    @Test
    fun `a market of a series not listed is not traded, but is still answered by its ticker`() {
        venue.adapter(series = HOURLY).use { adapter ->
            assertThat(adapter.instruments().map { it.code }).containsExactly("$HOURLY-26NOV16H03-UP")
            val settled = adapter.instrument(KalshiVenue.SETTLED)!!
            assertThat(settled.event!!.series).isEqualTo("KXHIGHNY")
            assertThat(settled.trading).isEqualTo(Tradability.DISABLED)
            assertThatThrownBy { adapter.trades(CURRY, 0L, 1L, 1) }
                .hasMessageContaining("kalshi lists no market $CURRY")
        }
    }

    @Test
    fun `a market between a floor above its cap is left out, the rest of its event listed`() {
        venue.adapter(series = "KXGDPYEAR").use { adapter ->
            val codes = adapter.instruments().map { it.code }

            assertThat(codes).hasSize(8).contains("KXGDPYEAR-27-B0.3").doesNotContain("KXGDPYEAR-27-B0.8")
        }
    }
}
